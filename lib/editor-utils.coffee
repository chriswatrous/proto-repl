{CompositeDisposable, Range, Point} = require 'atom'
edn_reader = require './proto_repl/edn_reader.js'

module.exports = EditorUtils =
  # Returns true if the position in the text editor is in a Markdown file in a
  # code block that contains Clojure.
  isPosInClojureMarkdown: (editor, pos)->
    scopeDesc = editor.scopeDescriptorForBufferPosition(pos)
    scopeDesc.scopes.indexOf("markup.code.clojure.gfm") >= 0

  # Finds a starting markdown section like "```clojure" searching backwards
  # from fromPos.
  findMarkdownCodeBlockStartPosition:  (editor, fromPos) ->
    startPos = null
    # We translate the search range forward in case the cursor is in the middle
    # of the declaration of the markdown block.
    scanRange = new Range([0,0], fromPos.translate(new Point(0, 10)))
    editor.backwardsScanInBufferRange /```clojure/ig, scanRange, (result) ->
      startPos = result.range.start.translate(new Point(1,0))
      result.stop()
    startPos

  # Finds a closing markdown section "```" searching forwards from fromPos.
  findMarkdownCodeBlockEndPosition:  (editor, fromPos) ->
    endPos = null
    scanRange = new Range(fromPos, editor.buffer.getEndPosition())
    editor.scanInBufferRange /```/g, scanRange, (result) ->
      endPos = result.range.start
      result.stop()
    endPos


  # Takes an editor and the position of a brace found in scanning and Determines
  # if the brace found at that position can be ignored. If the brace is in a
  # comment or inside a string it can be ignored.
  isIgnorableBrace: (editor, pos)->
    scopes = editor.scopeDescriptorForBufferPosition(pos).scopes
    scopes.indexOf("string.quoted.double.clojure") >= 0 ||
      scopes.indexOf("comment.line.semicolon.clojure") >= 0 ||
      scopes.indexOf("string.regexp.clojure") >= 0

  findBlockStartPosition:  (editor, fromPos) ->
    braceClosed =
      "}": 0
      ")": 0
      "]": 0
    openToClose =
      "{": "}"
      "[": "]"
      "(": ")"
    startPos = null
    editor.backwardsScanInBufferRange /[\{\}\[\]\(\)]/g, new Range([0,0], fromPos), (result) =>
      if !(@isIgnorableBrace(editor, result.range.start))
        c = ""+result.match[0]
        if braceClosed[c] != undefined
          braceClosed[c]++
        else
          braceClosed[openToClose[c]]--
          if braceClosed[openToClose[c]] == -1
            startPos = result.range.start
            result.stop()
    startPos

  findBlockEndPosition:  (editor, fromPos) ->
    braceOpened =
      "{": 0
      "(": 0
      "[": 0
    closeToOpen =
      "}": "{"
      "]": "["
      ")": "("
    endPos = null
    scanRange = new Range(fromPos, editor.buffer.getEndPosition())
    editor.scanInBufferRange /[\{\}\[\]\(\)]/g, scanRange, (result) =>
      if !(@isIgnorableBrace(editor, result.range.start))
        c = ""+result.match[0]
        if braceOpened[c] != undefined
          braceOpened[c]++
        else
          braceOpened[closeToOpen[c]]--
          if braceOpened[closeToOpen[c]] == -1
            endPos = result.range.start
            result.stop()
    endPos
