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

        for host in $scope.hosts
            for state in ["running", "frozen", "stopped"] when host.containers[state]
                for container in host.containers[state]
                    c = $.extend(true, {}, host)
                    c.host = container.name + " - " + host.host
                    $scope.curHosts.push c
      )

  startWS()

) 

window.angular.module('app' , ['utils'])
