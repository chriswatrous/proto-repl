NReplConnection = require './nrepl-connection'


module.exports=
# Represents a remotely running REPL process.
class RemoteReplProcess
  sendCommand: (code, options, resultHandler)->
    @conn.sendCommand(code, options, resultHandler)

  getCurrentNs: ->
    @conn.getCurrentNs()

  interrupt: ->
    @conn.interrupt()
    @replView.info("Interrupting")

  running: ()->
    @conn.connected()

  # Closes the remote connection.
  stop: ()->
    @stopCallback?()
    @conn.close()
