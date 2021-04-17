(ns proto-repl.sample.core
  (:require [clojure.walk :refer [prewalk]]
            [proto-repl.sample.other :as other]))

; TODO / FIXME Try all commands while not connected.

; remote-nrepl-connection (cmd-alt-y) --------------------------------------------------------------

; try to connect to invalid port
; connect to remote repl
; make sure tab works in the connection window
; FIXME try with badly formatted port or out of range
; FIXME doesn't work if window is already open
; TODO Maybe don't allow connection to be attempted with no port?


; clear-repl (cmd-shift-k) -------------------------------------------------------------------------

; execute expression then clear the REPL
(+ 1 1)


; execute-top-block (cmd-enter) --------------------------------------------------------------------

; FIXME cursor should stay in current editor window after first evaluation

; FIXME:
; - click in the non-button area of the REPL window toolbar
; - evaluate something in this file
; - cursor shoudld stay in this file

; run command on each character of expression
(+ 1 1)

; FIXME handle char literals
(comment
  (println \()
  (do (+ 1 1))
  (println \))
  (do (+ 1 1)))

; Make sure *e shows last exception.
(comment
  (.qwer 5)
  (do *e))

;reader conditional
(do #?(:clj "clojure"
       :cljs #js{}))

; FIXME should work on bare symbols, keywords, numbers, strings
map
123
123.456
100000000000000000000000000000000000000000000N
123.11111111111111111111111111111111111111111111111111111M
123/456
:qwer
"qwer"
#"asdf"
\a

; should work collections
{:a 1}
[1 2 3]
#{1 2 3} ; FIXME

; should work on quoted expressions
'(1 2 3) ; FIXME
`(1 2 3 ~map) ; FIXME

; FIXME should work on s horthand functions
#(+ 5 %)

; Run on whole comment block. Then test each expression inside block.
(comment
  (+ 1 1)
  #(+ 5 %) ; FIXME
  map ; FIXME
  123 ; FIXME
  100000000000000000000000000000000000000000000N ; FIXME
  123.11111111111111111111111111111111111111111111111111111M ; FIXME
  123/456 ; FIXME
  :qwer ; FIXME
  "qwer" ; FIXME
  #"asdf" ; FIXME
  {:a 1}
  [1 2 3]
  #{1 2 3} ; FIXME
  '(1 2 3) ; FIXME
  `(1 2 3 ~map)) ; FIXME

; FIXME should not lose precision when displayed
(do 100000000000000000000000000000000000000000000000000000000N)
(do 123.11111111111111111111111111111111111111111111111111111M)
(do 123/456)

; FIXME should display as character
(do \a)

; TODO would be nice if tagged literals showed up correctly
;      Maybe we need to do the pretty printing in the remote process instead of parsing and
;      reformatting the data in the editor
(ex-info "🤮" {})


; execute-block (alt-cmd-b) ------------------------------------------------------------------------

; run command with cursor inside the + expression
(when false (+ 1 1))


; execute-selected-text (shift-enter) --------------------------------------------------------------

; select different parts of expression and run command
; also run comand on symbols without selection
(reduce + (map inc [1 2 3]))
; FIXME should display executed expression same as with execute-top-block
; FIXME should work on +


; execute-text-entered-in-repl (shift-enter) -------------------------------------------------------

; Type something in the REPL input field and press shift-enter

; Type something in the REPL input field and then eval the expression below.
; The entered text should still be in the input field.
(comment
  (+ 1 1))


; exit-repl (ctrl-, e) -----------------------------------------------------------------------------

; FIXME works but not very useful because you can't reconnect without closing the REPL window


; interrupt (ctrl-shift-c) -------------------------------------------------------------------------

; TODO Maybe say "Nothing to interrupt." if no eval is waiting.
; TODO show namespace after interrupting

; execute these and interrupt before the first one returns
(comment
  (Thread/sleep 60000)
  (Thread/sleep 1000)
  (+ 1 1))


; list-ns-vars (alt-cmd-n or ctrl-, n) -------------------------------------------------------------
; list-ns-vars-with-docs (cmd-alt-shift-n) ---------------------------------------------------------

; FIXME key bindings get screwed up after cmd-alt-n:
; - ctrl key shows keyboard help but backtick key fixes
; - doesn't happen with ctrl-, n

(comment
  ; fully qualified
  clojure.string

  ; alias
  other

  ; unknown
  qwerasdf

  ; TODO use namespace part of symbol
  clojure.string/join

  ; TODO handle namespace alias
  other/g

  ; TODO get namespace for referred var
  prewalk)


; load-current-file (cmd-alt-shift-f) --------------------------------------------------------------

; TODO Should this just send the text of the file instead of telling the remote process to read
; the file from disk?
; - no need to save first
; - would still work when the path from the editor's point of view is different from the path from
;   the remote process's point of view

; run command with cursor outside of any expression
(println "loading" (namespace ::x))


; open-file-containing-var (cmd-alt-o) -------------------------------------------------------------

(defn f [] nil)

; run command on each symbol
(comment
  f ; same file
  other/g ; other file
  map ; Clojure standard library
  nrepl.cmdline/-main ; third party library
  clojure.string ; namespace FIXME should go to the beginning of the file
  qwer ; doesn't exist

  ; TODO would be nice if it worked on Java classes or at least print a message saying it doesn't
  ; work on Java classes
  clojure.lang.PersistentVector)


; print-var-code (cmd-alt-c) -----------------------------------------------------------------------

; TODO print with Clojure syntax highlighting
(comment map)

; FIXME doesn't work for this
(defn blah [])

; print-var-documentation (cmd-shift-d) ------------------------------------------------------------

; FIXME default key binding cmd-alt-d doesn't work
(comment map)

; get doc for this
(defn blah2
  "Sample docstring with special characters.
  <h1>should not be big</h1>
  ~`!@#$%^&*()_-+={[}]|\\:;'\"<,>.?/
  end"
  [])


; refresh-namespaces (cmd-alt-r) -------------------------------------------------------------------

; change x in other.clj then come back here and refresh
; run multiple times to see that it only refreshes files that changed
; TODO would be good to print the files that were reloaded
(comment
  (do other/x))


; super-refresh-namespaces (cmd-alt-shift-r) -------------------------------------------------------

; run multiple times to see that it always refreshes all files
; FIXME It says "Refresh complete" but it doesn't do anything.
(comment
  (do other/x))


; toggle-auto-scroll (cmd-alt-shift-s) -------------------------------------------------------------

; FIXME doesn't do anything
(comment
  (range 100))

; See test.clj for these commands
;   run-all-tests
;   run-test-under-cursor
;   run-tests-in-namespace


(comment
  ; make sure stdout, stderr, and return value are handled correctly
  ; FIXME escape characters so they aren't interpreted as HTML
  (do
    (println "stdout <h1>Should not be big.</h1>")
    (binding [*out* *err*]
      (println "stderr <h1>Should not be big.</h1>"))
    "return value <h1>Should not be big.</h1>")

  ; check exception formatting
  ; make sure file and line number are correct
  ; FIXME show ns line after error message
  (.qwer 45))


; autocomplete -------------------------------------------------------------------------------------

; scenarios:
; - not connected
; - connected but no proto-repl lib
; - connected with proto-repl lib, file not loaded
; - connected with proto-repl lib

(comment
  ; retype these
  clojure.string/join
  map)
