{Task, Emitter} = require 'atom'

Spinner = require './load-widget'

# temporary usage of copy of Atom Ink Tree view
TreeView = require './tree-view'

# Temporary performance helpers

startTime = ->
  window.performance.now()

logElapsed = (name, start)->
  elapsed = window.performance.now()-start
  console.log(name + " " + elapsed.toFixed() + " ms")


module.exports =

# Represents the REPL where code is executed and displayed. It is split into three
# parts. 1. The running process. 2. The nRepl connection, 3. The text editor where
# results are displayed.
class Repl
  emitter: null
  process: null
  replView: null
  ink: null
  extensionsFeature: null

  constructor: (@extensionsFeature)->
    @emitter = new Emitter
    @loadingIndicator = new Spinner()
