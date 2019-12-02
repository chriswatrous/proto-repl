{CompositeDisposable, Range, Point, Emitter} = require 'atom'
Highlights = require '../highlights.js'
CONSOLE_URI = 'atom://proto-repl/console'

module.exports =

# Wraps the Atom Ink console to allow it to work with Proto REPL.
class InkConsole
  # Writes results from Clojure execution to the REPL. The results are syntactically
  # highlighted as Clojure code.
  result: (text)->
    html = @highlighter.highlightSync
      fileContents: text
      scopeName: 'source.clojure'

    # Replace non-breaking spaces so that code can be correctly copied and pasted.
    html = html.replace(/&nbsp;/g, " ")

    div = document.createElement('div')
    div.innerHTML = html
    el = div.firstChild

    el.classList.add("proto-repl-console")
    el.style.fontSize = atom.config.get('editor.fontSize') + "px"
    el.style.lineHeight = atom.config.get('editor.lineHeight')

    @console.result(el, {error: false})

  # Displays code that was executed in the REPL adding it to the history.
  displayExecutedCode: (code)->
    inputCell = @console.getInput()
    if not (inputCell.editor.getText())
      inputCell.editor.setText(code)
    @console.logInput()
    @console.done()
    @console.input()

  # Executes the text that was entered in the entry area
  executeEnteredText: (inputCell={}) ->
    editor = @console.getInput().editor
    return null unless editor.getText().trim()
    code = editor.getText()

    # This manually adds the executed code to the REPL if it's code that was
    # entered there. Even if the setting is disabled it still behaves like
    # you expect a REPL to behave.
    if not atom.config.get('proto-repl.displayExecutedCodeInRepl')
      @displayExecutedCode(code)

    window.protoRepl.executeCode(code, displayCode: code, doBlock: true)
