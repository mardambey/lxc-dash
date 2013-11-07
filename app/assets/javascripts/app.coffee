utils = window.angular.module('utils' , [])

utils.controller('HostsController', ($scope, $http) ->

  startWS = ->
    wsUrl = jsRoutes.controllers.AppController.indexWS().webSocketURL()
  
    $scope.socket = new WebSocket(wsUrl)
    $scope.socket.onmessage = (msg) ->
      $scope.$apply( ->
        console.log "received : #{msg}"
        $scope.hosts = JSON.parse(msg.data).data
      )

  startWS()

) 

window.angular.module('app' , ['utils'])
