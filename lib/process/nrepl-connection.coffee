nrepl = require('jg-nrepl-client')
ClojureVersion = require './clojure-version'
EditorUtils = require '../editor-utils'

# DEFAULT_NS = "user"

module.exports =

class NReplConnection
  # # The nrepl connection
  # conn: null
  #
  # # The standard nREPL session
  # session: null
  #
  # # A separate nREPL session for sending commands in which we do not want the result
  # # value sent to the REPL.
  # cmdSession: null
  #
  # # A map of sessions to name. These are created on demand when needed.
  # sessionsByName: {}
  #
  # clojureVersion: null

  determineClojureVersion: (callback)->
    @conn.eval "*clojure-version*", "user", @session, (err, messages)=>
      value = (msg.value for msg in messages)[0]
      @clojureVersion = new ClojureVersion(window.protoRepl.parseEdn(value))
      unless @clojureVersion.isSupportedVersion()
        atom.notifications.addWarning "WARNING: This version of Clojure is not supported by Proto REPL. You may experience issues.",
          dismissable: true
      callback()

  startMessageHandling: (messageHandler)->
    # Log any output from the nRepl connection messages
    @conn.messageStream.on "messageSequence", (id, messages)=>
      # Skip sending messages if the namespace isn't found.
      unless @namespaceNotFound(messages)
        for msg in messages

          # Set the current ns, but only if the message is in response
          # to something sent by the user through the REPL
          if msg.ns && msg.session == @session
            @currentNs = msg.ns

          if msg.session == @session
            messageHandler(msg)
          else if msg.session == @cmdSession && msg.out
            # I don't like that we have to have this much logic here about
            # what messages to send to the handler or not. We have to allow output
            # for the cmdSession though.
            messageHandler(msg)

  # Returns true if the connection is open.
  connected: ->
    @conn != null

  getCurrentNs: ->
    @currentNs

  # Returns true if the code might have a reader conditional in it
  # Avoids unnecesary eval'ing for regular code.
  codeMayContainReaderConditional: (code)->
    code.includes("#?")

  # Wraps the given code in an eval and a read-string. This is required for
  # handling reader conditionals. http://clojure.org/guides/reader_conditionals
  wrapCodeInReadEval: (code)->
    if @clojureVersion?.isReaderConditionalSupported() && @codeMayContainReaderConditional(code)
      escapedStr = EditorUtils.escapeClojureCodeInString(code)
      "(eval (read-string {:read-cond :allow} #{escapedStr}))"
    else
      code

  # Returns true if any of the messages indicate the namespace wasn't found.
  namespaceNotFound: (messages)->
    for msg in messages
      if msg.status?.length > 0
        return true if msg.status[0] == "namespace-not-found"
