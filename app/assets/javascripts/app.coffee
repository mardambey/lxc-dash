utils = window.angular.module('utils' , [])


utils.filter('dot2Uscore',  ->
  (str) ->
    str.replace(/\./g, "_")

).filter('stateToLabel',  ->
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

  $scope.$watch('curHost', ->
    cb = ->
        return if not $scope.curHost
        running = $scope.curHost.containers["running"]

        for ctr in running
            cpuData = []
            for i in [0...ctr.cpuSystem.length - 1]
                usageDiff = (ctr.cpuSystem[i+1].value - ctr.cpuSystem[i].value) / 1024 / 1024
                value = [ctr.cpuSystem[i+1].time, usageDiff]
                cpuData.push(value)
                console.log(ctr.cpuSystem[i])

            memData = []
            for c in ctr.cpuSystem
                memData.push(c.value)

            cpuMinX = cpuData[0][0]-1
            cpuMaxX = cpuData[cpuData.length - 1][0]+1

            # $("#cpugraph-#{ctr.name.replace(/\./g, '_')}").sparkline(cpuData, { type: "bar", chartRangeMinX: cpuMinX, chartRangeMaxX: cpuMaxX })
            # $("#memgraph-#{ctr.name.replace(/\./g, '_')}").sparkline(cpuData, {type: "bar", barColor: "blue"})

    setTimeout cb, 100
  , true)

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
