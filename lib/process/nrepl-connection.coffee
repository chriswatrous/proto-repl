nrepl = require('jg-nrepl-client')
ClojureVersion = require './clojure-version'
EditorUtils = require '../editor-utils'

module.exports =

class NReplConnection
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

  # Returns true if the code might have a reader conditional in it
  # Avoids unnecesary eval'ing for regular code.
  codeMayContainReaderConditional: (code)->
    code.includes("#?")

  # Returns true if any of the messages indicate the namespace wasn't found.
  namespaceNotFound: (messages)->
    for msg in messages
      if msg.status?.length > 0
        return true if msg.status[0] == "namespace-not-found"
