utils = window.angular.module('utils' , [])

utils.filter('stateToLabel',  ->
  (state) ->
    if (state == "running")
      "success"
    else if (state == "stopped")
      "danger"
    else if (state == "frozen")
      "info"
    else
      "default"

).controller('HostsController', ($scope, $http) ->

  $scope.searchTerm = undefined
  $scope.hosts = undefined
  $scope.matches = undefined
  $scope.curHosts = undefined

  $scope.search = ($event) ->
    console.log("searchTerm=#{$scope.searchTerm}")
    $scope.matches = (host for host of $scope.hosts when host.indexOf($scope.searchTerm) >= 0)
    console.log("matches=#{$scope.matches}")
    $scope.curHosts = {}

    # TODO: display hosts and containers, use groups / separators

    for match in $scope.matches
      $scope.curHosts[match] = $scope.hosts[match]

  startWS = ->
    wsUrl = jsRoutes.controllers.AppController.indexWS().webSocketURL()
  
    $scope.socket = new WebSocket(wsUrl)
    $scope.socket.onmessage = (msg) ->
      $scope.$apply( ->
        console.log "received : #{msg.data}"
        $scope.hosts = JSON.parse(msg.data).data
        $scope.curHosts = $scope.hosts
      )

  startWS()

) 

window.angular.module('app' , ['utils'])
