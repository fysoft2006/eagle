/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(function () {
	/**
	 * `register` without params will load the module which using require
	 */
	register(function (jpmApp) {
		var JOB_STATES = ["NEW", "NEW_SAVING", "SUBMITTED", "ACCEPTED", "RUNNING", "FINISHED", "SUCCEEDED", "FAILED", "KILLED"];

		jpmApp.controller("listCtrl", function ($wrapState, $element, $scope, $q, PageConfig, Time, Entity, JPM) {
			// Initialization
			PageConfig.title = "YARN Jobs";
			$scope.getStateClass = JPM.getStateClass;
			$scope.tableScope = {};

			$scope.site = $wrapState.param.siteId;
			$scope.searchPathList = [["tags", "jobId"], ["tags", "user"], ["tags", "queue"], ["currentState"]];

			function getCommonOption(left) {
				return {
					grid: {
						left: left,
						bottom: 20,
						containLabel: false
					}
				};
			}

			$scope.chartLeftOption = getCommonOption(45);
			$scope.chartRightOption = getCommonOption(80);

			$scope.fillSearch = function (key) {
				$("#jobList").find(".search-box input").val(key).trigger('input');
			};

			$scope.refreshList = function () {
				var startTime = Time.startTime();
				var endTime = Time.endTime();

				// ==========================================================
				// =                        Job List                        =
				// ==========================================================

				/**
				 * @namespace
				 * @property {[]} jobList
				 * @property {{}} jobList.tags						unique job key
				 * @property {string} jobList.tags.jobId			Job Id
				 * @property {string} jobList.tags.user				Submit user
				 * @property {string} jobList.tags.queue			Queue
				 * @property {string} jobList.currentState			Job state
				 * @property {string} jobList.submissionTime		Submission time
				 * @property {string} jobList.startTime				Start time
				 * @property {string} jobList.endTime				End time
				 * @property {string} jobList.numTotalMaps			Maps count
				 * @property {string} jobList.numTotalReduces		Reduce count
				 * @property {string} jobList.runningContainers		Running container count
				 */

				$scope.jobList = Entity.merge($scope.jobList, JPM.jobList({site: $scope.site}, startTime, endTime, [
					"jobId",
					"jobDefId",
					"jobName",
					"jobExecId",
					"currentState",
					"user",
					"queue",
					"submissionTime",
					"startTime",
					"endTime",
					"numTotalMaps",
					"numTotalReduces",
					"runningContainers"
				], 100000));
				$scope.jobStateList = [];

				$scope.jobList._then(function () {
					var now = Time();
					var jobStates = {};
					$.each($scope.jobList, function (i, job) {
						jobStates[job.currentState] = (jobStates[job.currentState] || 0) + 1;
						job.duration = Time.diff(job.startTime, job.endTime || now);
					});

					$scope.jobStateList = $.map(JOB_STATES, function (state) {
						var value = jobStates[state];
						delete  jobStates[state];
						if(!value) return null;
						return {
							key: state,
							value: value
						};
					});

					$.each(jobStates, function (key, value) {
						$scope.jobStateList.push({
							key: key,
							value: value
						});
					});
				});

				// ===========================================================
				// =                     Statistic Trend                     =
				// ===========================================================
				var interval = Time.diffInterval(startTime, endTime);
				var intervalMin = interval / 1000 / 60;
				var trendStartTime = Time.align(startTime, interval);
				var trendEndTime = Time.align(endTime, interval);
				var trendStartTimestamp = trendStartTime.valueOf();

				// ==================== Running Job Trend ====================
				JPM.get(JPM.getQuery("MR_JOB_COUNT"), {
					site: $scope.site,
					intervalInSecs: interval / 1000,
					durationBegin: Time.format(trendStartTime),
					durationEnd: Time.format(trendEndTime)
				}).then(
					/**
					 * @param {{}} res
					 * @param {{}} res.data
					 * @param {[]} res.data.jobCounts
					 */
					function (res) {
						var data = res.data;
						var jobCounts = data.jobCounts;
						var jobTypesData = {};
						$.each(jobCounts,
							/**
							 * @param index
							 * @param {{}} jobCount
							 * @param {{}} jobCount.timeBucket
							 * @param {{}} jobCount.jobCountByType
							 */
							function (index, jobCount) {
								$.each(jobCount.jobCountByType, function (type, count) {
									var countList = jobTypesData[type] = jobTypesData[type] || [];
									countList[index] = count;
								});
							});

						$scope.runningTrendSeries = $.map(jobTypesData, function (countList, type) {
							var dataList = [];
							for(var i = 0 ; i < jobCounts.length ; i += 1) {
								dataList[i] = {
									x: trendStartTimestamp + i * interval,
									y: countList[i] || 0
								};
							}

							return {
								name: type,
								type: "line",
								stack: "job",
								showSymbol: false,
								areaStyle: {normal: {}},
								data: dataList
							};
						});
					});

				// ================= Running Container Trend =================
				JPM.aggMetricsToEntities(
					JPM.aggMetrics({site: $scope.site}, "hadoop.cluster.runningcontainers", ["site"], "max(value)", intervalMin, trendStartTime, trendEndTime),
					true)._promise.then(function (list) {
					$scope.runningContainersSeries = [JPM.metricsToSeries("Running Containers", list, {areaStyle: {normal: {}}})];
				});

				// ================= Allocated vCores Trend ==================
				JPM.aggMetricsToEntities(
					JPM.aggMetrics({site: $scope.site}, "hadoop.cluster.allocatedvcores", ["site"], "max(value)", intervalMin, trendStartTime, trendEndTime),
					true)._promise.then(function (list) {
					$scope.allocatedvcoresSeries = [JPM.metricsToSeries("Allocated vCores", list, {areaStyle: {normal: {}}})];
				});

				// ==================== AllocatedMB Trend ====================
				var allocatedMBEntities = JPM.aggMetricsToEntities(
					JPM.aggMetrics({site: $scope.site}, "hadoop.cluster.allocatedmb", ["site"], "max(value)", intervalMin, trendStartTime, trendEndTime),
					true);

				var totalMemoryEntities = JPM.aggMetricsToEntities(
					JPM.aggMetrics({site: $scope.site}, "hadoop.cluster.totalmemory", ["site"], "max(value)", intervalMin, trendStartTime, trendEndTime),
					true);

				$q.all([allocatedMBEntities._promise, totalMemoryEntities._promise]).then(function (args) {
					var allocatedMBList = args[0];
					var totalMemoryList = args[1];

					var mergedList = $.map(allocatedMBList, function (obj, index) {
						var value = obj.value[0] / totalMemoryList[index].value[0] * 100 || 0;
						return $.extend({}, obj, {
							value: [value]
						});
					});

					$scope.allocatedMBSeries = [JPM.metricsToSeries("Allocated GB", mergedList, {areaStyle: {normal: {}}})];
					$scope.allocatedMBOption = $.extend({}, $scope.chartRightOption, {
						yAxis: [{
							axisLabel: {
								formatter: "{value}%"
							},
							max: 100
						}],
						tooltip: {
							formatter: function (points) {
								var point = points[0];
								var index = point.dataIndex;
								return point.name + "<br/>" +
									'<span style="display:inline-block;margin-right:5px;border-radius:10px;width:9px;height:9px;background-color:' + point.color + '"></span> ' +
									point.seriesName + ": " + common.number.format(allocatedMBList[index].value[0] / 1024, 2);
							}
						}
					});
				});
			};

			Time.onReload($scope.refreshList, $scope);

			// Load list
			$scope.refreshList();
		});
	});
})();
