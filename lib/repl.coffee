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

  running: ->
    @process?.running()

  # Displays some result data inline. tree is a recursive structure expected to
  # be of the shape like the following.
  # ["text for display", {button options}, [childtree1, childtree2, ...]]
  displayInline: (editor, range, tree, error=false)->
    end = range.end.row

    # Remove the existing view if there is one
    @ink.Result.removeLines(editor, end, end)

    # Defines a recursive function that can convert the tree of values to
    # display into an Atom Ink tree view. Sub-branches are expandable.
    recurseTree = ([head, button_options, children...])=>
      if children && children.length > 0
        childViews = children.map  (x)=>
          if x instanceof Array
            recurseTree(x)
          else
            # The button options here are for the head not the child
            TreeView.leafView(x,{})
        TreeView.treeView(head, childViews, button_options)
      else
        TreeView.leafView(head, button_options || {})
    view = recurseTree(tree)

    # Add new inline view
    r = new @ink.Result editor, [end, end],
          content: view,
          error: error,
          type: if error then 'block' else 'inline',
          scope: 'proto-repl'

  # Makes an inline displaying result handler
  # * editor - the text editor to show the inline display in
  # * range - the range of code to display the inline result next to
  # * valueToTreeFn - a function that can convert the result value into the tree
  # of content for inline display.
  makeInlineHandler: (editor, range, valueToTreeFn)->
    (result) =>
      isError = false
      if result.value
        tree = valueToTreeFn(result.value)
      else
        tree = [result.error]
        isError = true
      @displayInline(editor, range, tree, isError)

  # Checks if we need to wrap the code in a do block
  needsDoBlock: (code) ->
    # currently only white lists for single symbol/keyword, such as :cljs/quit
    if code.match(/^\s*[A-Za-z0-9\-!?.<>:\/*=+_]+\s*$/g) != null
      false
    # or single un-nested call, such as (fig-status)
    else if code.match(/^\s*\([^\(\)]+\)\s*$/g) != null
      false
    else
      true

  # # Executes the text that was entered in the entry area
  executeEnteredText: ->
    return null unless @running()
    @replView.executeEnteredText()

  exit: ->
    return null unless @running()
    @info("Stopping REPL")
    @process.stop()
    @process = null

  interrupt: ->
    @loadingIndicator.clearAll()
    @process.interrupt()

  clear: ->
    @replView.clear()


  # Helpers for adding text to the REPL.
  info: (text)->
    @replView?.info(text)

  stderr: (text)->
    @replView?.stderr(text)

  stdout: (text)->
    @replView?.stdout(text)

  doc: (text)->
    @replView?.doc(text)
