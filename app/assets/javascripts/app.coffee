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
  $scope.curHosts = undefined
  $scope.curHost = undefined

  startWS = ->
    wsUrl = jsRoutes.controllers.AppController.indexWS().webSocketURL()
  
    $scope.socket = new WebSocket(wsUrl)
    $scope.socket.onmessage = (msg) ->
      $scope.$apply( ->
        console.log "received : #{msg.data}"
        $scope.hosts = JSON.parse(msg.data)
        $scope.curHosts = $scope.hosts
      )

  startWS()

) 

window.angular.module('app' , ['utils'])
