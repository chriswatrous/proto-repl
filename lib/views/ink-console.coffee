{CompositeDisposable, Range, Point, Emitter} = require 'atom'
Highlights = require '../highlights.js'
CONSOLE_URI = 'atom://proto-repl/console'

module.exports =

# Wraps the Atom Ink console to allow it to work with Proto REPL.
class InkConsole
  # Displays code that was executed in the REPL adding it to the history.
  displayExecutedCode: (code)->
    inputCell = @console.getInput()
    if not (inputCell.editor.getText())
      inputCell.editor.setText(code)
    @console.logInput()
    @console.done()
    @console.input()
