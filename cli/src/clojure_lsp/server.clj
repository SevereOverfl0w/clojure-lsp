(ns clojure-lsp.server
  (:require
   [clojure-lsp.clojure-coercer :as clojure-coercer]
   [clojure-lsp.db :as db]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.feature.refactor :as f.refactor]
   [clojure-lsp.feature.semantic-tokens :as semantic-tokens]
   [clojure-lsp.feature.test-tree :as f.test-tree]
   [clojure-lsp.handlers :as handler]
   [clojure-lsp.logger :as logger]
   [clojure-lsp.nrepl :as nrepl]
   [clojure-lsp.producer :as producer]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure.core.async :as async]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.json-rpc.messages :as lsp.messages]
   [lsp4clj.liveness-probe :as lsp.liveness-probe]
   [lsp4clj.server :as lsp.server]
   [taoensso.timbre :as timbre]))

(set! *warn-on-reflection* true)

(def diagnostics-debounce-ms 100)
(def change-debounce-ms 300)
(def created-watched-files-debounce-ms 500)

(def known-files-pattern "**/*.{clj,cljs,cljc,cljd,edn,bb,clj_kondo}")

(defn log! [level args fmeta]
  (timbre/log! level :p args {:?line (:line fmeta)
                              :?file (:file fmeta)
                              :?ns-str (:ns-str fmeta)}))

(defn log-wrapper-fn
  [level & args]
  ;; NOTE: this does not do compile-time elision because the level isn't a constant.
  ;; We don't really care because we always log all levels.
  (timbre/log! level :p args))

(defmacro conform-or-log
  [spec value]
  (let [fmeta (assoc (meta &form)
                     :file *file*
                     :ns-str (str *ns*))]
    `(coercer/conform-or-log
       (fn [& args#]
         (log! :error args# ~fmeta))
       ~spec
       ~value)))

(defrecord TimbreLogger []
  logger/ILogger
  (setup [this]
    (let [log-path (str (java.io.File/createTempFile "clojure-lsp." ".out"))]
      (timbre/merge-config! {:middleware [#(assoc % :hostname_ "")]
                             :appenders {:println {:enabled? false}
                                         :spit (timbre/spit-appender {:fname log-path})}})
      (timbre/handle-uncaught-jvm-exceptions!)
      (logger/set-logger! this)
      log-path))

  (set-log-path [_this log-path]
    (timbre/merge-config! {:appenders {:spit (timbre/spit-appender {:fname log-path})}}))

  (-info [_this fmeta arg1] (log! :info [arg1] fmeta))
  (-info [_this fmeta arg1 arg2] (log! :info [arg1 arg2] fmeta))
  (-info [_this fmeta arg1 arg2 arg3] (log! :info [arg1 arg2 arg3] fmeta))
  (-warn [_this fmeta arg1] (log! :warn [arg1] fmeta))
  (-warn [_this fmeta arg1 arg2] (log! :warn [arg1 arg2] fmeta))
  (-warn [_this fmeta arg1 arg2 arg3] (log! :warn [arg1 arg2 arg3] fmeta))
  (-error [_this fmeta arg1] (log! :error [arg1] fmeta))
  (-error [_this fmeta arg1 arg2] (log! :error [arg1 arg2] fmeta))
  (-error [_this fmeta arg1 arg2 arg3] (log! :error [arg1 arg2 arg3] fmeta))
  (-debug [_this fmeta arg1] (log! :debug [arg1] fmeta))
  (-debug [_this fmeta arg1 arg2] (log! :debug [arg1 arg2] fmeta))
  (-debug [_this fmeta arg1 arg2 arg3] (log! :debug [arg1 arg2 arg3] fmeta)))

(defrecord ^:private ClojureLspProducer
           [server db*]
  producer/IProducer

  (publish-diagnostic [_this diagnostic]
    (logger/debug (format "Publishing %s diagnostics for %s" (count (:diagnostics diagnostic)) (:uri diagnostic)))
    (shared/logging-task
      :publish-diagnostics
      (->> diagnostic
           (conform-or-log ::coercer/publish-diagnostics-params)
           (lsp.server/send-notification server "textDocument/publishDiagnostics"))))

  (refresh-code-lens [_this]
    (when (get-in @db* [:client-capabilities :workspace :code-lens :refresh-support])
      (lsp.server/send-request server "workspace/codeLens/refresh" nil)))

  (publish-workspace-edit [_this edit]
    (let [request (->> {:edit edit}
                       (conform-or-log ::coercer/workspace-edit-params)
                       (lsp.server/send-request server "workspace/applyEdit"))
          response (lsp.server/deref-or-cancel request 10e3 ::timeout)]
      (if (= ::timeout response)
        (logger/error "No reponse from client after 10 seconds.")
        response)))

  (show-document-request [_this document-request]
    (when (get-in @db* [:client-capabilities :window :show-document])
      (logger/info "Requesting to show on editor the document" document-request)
      (->> document-request
           (conform-or-log ::coercer/show-document-request)
           (lsp.server/send-request server "window/showDocument"))))

  (publish-progress [_this percentage message progress-token]
    ;; ::coercer/notify-progress
    (->> (lsp.messages/work-done-progress percentage message (or progress-token "clojure-lsp"))
         (lsp.server/send-notification server "$/progress")))

  (show-message-request [_this message type actions]
    (let [request (->> {:type    type
                        :message message
                        :actions actions}
                       (conform-or-log ::coercer/show-message-request)
                       (lsp.server/send-request server "window/showMessageRequest"))
          response (lsp.server/deref-or-cancel request 10e3 ::timeout)]
      (when-not (= response ::timeout)
        (:title response))))

  (show-message [_this message type extra]
    (let [message-content {:message message
                           :type type
                           :extra extra}]
      (logger/info message-content)
      (->> message-content
           (conform-or-log ::coercer/show-message)
           (lsp.server/send-notification server "window/showMessage"))))

  (refresh-test-tree [_this uris]
    (async/thread
      (let [db @db*]
        (when (some-> db :client-capabilities :experimental :test-tree)
          (shared/logging-task
            :refreshing-test-tree
            (doseq [uri uris]
              (some->> (f.test-tree/tree uri db)
                       (conform-or-log ::clojure-coercer/publish-test-tree-params)
                       (lsp.server/send-notification server "clojure/textDocument/testTree")))))))))

;;;; clojure experimental features

(defmethod lsp.server/receive-request "clojure/dependencyContents" [_ components params]
  (->> params
       (handler/dependency-contents components)
       (conform-or-log ::coercer/uri)))
(defmethod lsp.server/receive-request "clojure/serverInfo/raw" [_ components _params]
  (handler/server-info-raw components))
(defmethod lsp.server/receive-notification "clojure/serverInfo/log" [_ components _params]
  (future
    (try
      (handler/server-info-log components)
      (catch Throwable e
        (logger/error e)
        (throw e)))))
(defmethod lsp.server/receive-request "clojure/cursorInfo/raw" [_ components params]
  (handler/cursor-info-raw components params))
(defmethod lsp.server/receive-notification "clojure/cursorInfo/log" [_ components params]
  (future
    (try
      (handler/cursor-info-log components params)
      (catch Throwable e
        (logger/error e)
        (throw e)))))
(defmethod lsp.server/receive-request "clojure/clojuredocs/raw" [_ components params]
  (handler/clojuredocs-raw components params))

;;;; Document sync features

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_synchronization

(defmethod lsp.server/receive-notification "textDocument/didOpen" [_ components params]
  (handler/did-open components params))

(defmethod lsp.server/receive-notification "textDocument/didChange" [_ components params]
  (handler/did-change components params))

(defmethod lsp.server/receive-notification "textDocument/didSave" [_ components params]
  (future
    (try
      (handler/did-save components params)
      (catch Throwable e
        (logger/error e)
        (throw e)))))

(defmethod lsp.server/receive-notification "textDocument/didClose" [_ components params]
  (handler/did-close components params))

(defmethod lsp.server/receive-request "textDocument/references" [_ components params]
  (->> params
       (handler/references components)
       (conform-or-log ::coercer/locations)))

(defmethod lsp.server/receive-request "textDocument/completion" [_ components params]
  (->> params
       (handler/completion components)
       (conform-or-log ::coercer/completion-items)))

(defmethod lsp.server/receive-request "completionItem/resolve" [_ components item]
  (->> item
       (conform-or-log ::coercer/input.completion-item)
       (handler/completion-resolve-item components)
       (conform-or-log ::coercer/completion-item)))

(defmethod lsp.server/receive-request "textDocument/prepareRename" [_ components params]
  (->> params
       (handler/prepare-rename components)
       (conform-or-log ::coercer/prepare-rename-or-error)))

(defmethod lsp.server/receive-request "textDocument/rename" [_ components params]
  (->> params
       (handler/rename components)
       (conform-or-log ::coercer/workspace-edit-or-error)))

(defmethod lsp.server/receive-request "textDocument/hover" [_ components params]
  (->> params
       (handler/hover components)
       (conform-or-log ::coercer/hover)))

(defmethod lsp.server/receive-request "textDocument/signatureHelp" [_ components params]
  (->> params
       (handler/signature-help components)
       (conform-or-log ::coercer/signature-help)))

(defmethod lsp.server/receive-request "textDocument/formatting" [_ components params]
  (->> params
       (handler/formatting components)
       (conform-or-log ::coercer/edits)))

(def ^:private formatting (atom false))

(defmethod lsp.server/receive-request "textDocument/rangeFormatting" [_this components params]
  (when (compare-and-set! formatting false true)
    (try
      (->> params
           (handler/range-formatting components)
           (conform-or-log ::coercer/edits))
      (catch Exception e
        (logger/error e))
      (finally
        (reset! formatting false)))))

(defmethod lsp.server/receive-request "textDocument/codeAction" [_ components params]
  (->> params
       (handler/code-actions components)
       (conform-or-log ::coercer/code-actions)))

(defmethod lsp.server/receive-request "textDocument/codeLens" [_ components params]
  (->> params
       (handler/code-lens components)
       (conform-or-log ::coercer/code-lenses)))

(defmethod lsp.server/receive-request "codeLens/resolve" [_ components params]
  (->> params
       (handler/code-lens-resolve components)
       (conform-or-log ::coercer/code-lens)))

(defmethod lsp.server/receive-request "textDocument/definition" [_ components params]
  (->> params
       (handler/definition components)
       (conform-or-log ::coercer/location)))

(defmethod lsp.server/receive-request "textDocument/declaration" [_ components params]
  (->> params
       (handler/declaration components)
       (conform-or-log ::coercer/location)))

(defmethod lsp.server/receive-request "textDocument/implementation" [_ components params]
  (->> params
       (handler/implementation components)
       (conform-or-log ::coercer/locations)))

(defmethod lsp.server/receive-request "textDocument/documentSymbol" [_ components params]
  (->> params
       (handler/document-symbol components)
       (conform-or-log ::coercer/document-symbols)))

(defmethod lsp.server/receive-request "textDocument/documentHighlight" [_ components params]
  (->> params
       (handler/document-highlight components)
       (conform-or-log ::coercer/document-highlights)))

(defmethod lsp.server/receive-request "textDocument/semanticTokens/full" [_ components params]
  (->> params
       (handler/semantic-tokens-full components)
       (conform-or-log ::coercer/semantic-tokens)))

(defmethod lsp.server/receive-request "textDocument/semanticTokens/range" [_ components params]
  (->> params
       (handler/semantic-tokens-range components)
       (conform-or-log ::coercer/semantic-tokens)))

(defmethod lsp.server/receive-request "textDocument/prepareCallHierarchy" [_ components params]
  (->> params
       (handler/prepare-call-hierarchy components)
       (conform-or-log ::coercer/call-hierarchy-items)))

(defmethod lsp.server/receive-request "callHierarchy/incomingCalls" [_ components params]
  (->> params
       (handler/call-hierarchy-incoming components)
       (conform-or-log ::coercer/call-hierarchy-incoming-calls)))

(defmethod lsp.server/receive-request "callHierarchy/outgoingCalls" [_ components params]
  (->> params
       (handler/call-hierarchy-outgoing components)
       (conform-or-log ::coercer/call-hierarchy-outgoing-calls)))

(defmethod lsp.server/receive-request "textDocument/linkedEditingRange" [_ components params]
  (->> params
       (handler/linked-editing-ranges components)
       (conform-or-log ::coercer/linked-editing-ranges-or-error)))

;;;; Workspace features

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspaceFeatures

(defmethod lsp.server/receive-request "workspace/executeCommand" [_ components params]
  (future
    (try
      (handler/execute-command components params)
      (catch Throwable e
        (logger/error e)
        (throw e))))
  nil)

(defmethod lsp.server/receive-notification "workspace/didChangeConfiguration" [_ _components params]
  (logger/warn params))

(defmethod lsp.server/receive-notification "workspace/didChangeWatchedFiles" [_ components params]
  (->> params
       (conform-or-log ::coercer/did-change-watched-files-params)
       (handler/did-change-watched-files components)))

(defmethod lsp.server/receive-request "workspace/symbol" [_ components params]
  (->> params
       (handler/workspace-symbols components)
       (conform-or-log ::coercer/workspace-symbols)))

(defmethod lsp.server/receive-request "workspace/willRenameFiles" [_ components params]
  (->> params
       (handler/will-rename-files components)
       (conform-or-log ::coercer/workspace-edit)))

(defn capabilities [settings]
  (conform-or-log
    ::coercer/server-capabilities
    {:document-highlight-provider true
     :hover-provider true
     :declaration-provider true
     :implementation-provider true
     :signature-help-provider []
     :call-hierarchy-provider true
     :linked-editing-range-provider true
     :code-action-provider (vec (vals coercer/code-action-kind))
     :code-lens-provider true
     :references-provider true
     :rename-provider true
     :definition-provider true
     :document-formatting-provider ^Boolean (:document-formatting? settings)
     :document-range-formatting-provider ^Boolean (:document-range-formatting? settings)
     :document-symbol-provider true
     :workspace-symbol-provider true
     :workspace {:file-operations {:will-rename {:filters [{:scheme "file"
                                                            :pattern {:glob known-files-pattern
                                                                      :matches "file"}}]}}}
     :semantic-tokens-provider (when (or (not (contains? settings :semantic-tokens?))
                                         (:semantic-tokens? settings))
                                 {:token-types semantic-tokens/token-types-str
                                  :token-modifiers semantic-tokens/token-modifiers-str
                                  :range true
                                  :full true})
     :execute-command-provider f.refactor/available-refactors
     :text-document-sync (:text-document-sync-kind settings)
     :completion-provider {:resolve-provider true :trigger-characters [":" "/"]}
     :experimental {:test-tree true
                    :cursor-info true
                    :server-info true
                    :clojuredocs true}}))

(defn client-settings [params]
  (-> params
      :initialization-options
      (or {})
      (settings/clean-client-settings)))

(defn ^:private exit []
  (logger/info "Exiting...")
  (shutdown-agents)
  (System/exit 0))

;;;; Lifecycle messages

;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#lifeCycleMessages

(defmethod lsp.server/receive-request "initialize" [_ {:keys [db*] :as components} params]
  (logger/info "Initializing...")
  (handler/initialize components
                      (:root-uri params)
                      ;; TODO: lsp2clj do we need any of the client capabilities
                      ;; coercion that used to happen?
                      (:capabilities params)
                      (client-settings params)
                      (some-> params :work-done-token str))
  (when-let [parent-process-id (:process-id params)]
    (lsp.liveness-probe/start! parent-process-id log-wrapper-fn exit))
  ;; TODO: lsp2clj do we need any of the server capabilities coercion that used to happen?
  {:capabilities (capabilities (settings/all @db*))})

(defmethod lsp.server/receive-notification "initialized" [_ {:keys [server]} _params]
  (logger/info "Initialized!")
  (->> {:registrations [{:id "id"
                         :method "workspace/didChangeWatchedFiles"
                         :register-options {:watchers [{:glob-pattern known-files-pattern}]}}]}
       (lsp.server/send-request server "client/registerCapability")))

(defmethod lsp.server/receive-request "shutdown" [_ {:keys [db* server]} _params]
  (logger/info "Shutting down...")
  (lsp.server/shutdown server)  ;; blocks, waiting for previously received messages to be processed
  (reset! db* db/initial-db) ;; resets db for dev
  nil)

(defmethod lsp.server/receive-notification "exit" [_ _components _params]
  (exit))

(defmethod lsp.server/receive-notification "$/cancelRequest" [_ _ _])

(defmacro ^:private safe-async-task [task-name & task-body]
  `(async/thread
     (loop []
       (try
         ~@task-body
         (catch Exception e#
           (logger/error e# (format "Error during async task %s" ~task-name))))
       (recur))))

(defn ^:private spawn-async-tasks!
  [{:keys [producer] :as components}]
  (let [debounced-diags (shared/debounce-by db/diagnostics-chan diagnostics-debounce-ms :uri)
        debounced-changes (shared/debounce-by db/current-changes-chan change-debounce-ms :uri)
        debounced-created-watched-files (shared/debounce-all db/created-watched-files-chan created-watched-files-debounce-ms)]
    (safe-async-task
      :edits
      (producer/publish-workspace-edit producer (async/<!! db/edits-chan)))
    (safe-async-task
      :diagnostics
      (producer/publish-diagnostic producer (async/<!! debounced-diags)))
    (safe-async-task
      :changes
      (let [changes (async/<!! debounced-changes)] ;; do not put inside shared/logging-task; parked time gets included in task time
        (shared/logging-task
          :analyze-file
          (f.file-management/analyze-changes changes components))))
    (safe-async-task
      :watched-files
      (let [created-watched-files (async/<!! debounced-created-watched-files)] ;; do not put inside shared/logging-task; parked time gets included in task time
        (shared/logging-task
          :analyze-created-files-in-watched-dir
          (f.file-management/analyze-watched-created-files! created-watched-files components))))))

(defn ^:private monitor-server-logs [{:keys [trace-ch log-ch]}]
  (when trace-ch
    (async/go-loop []
      ;; TODO: send traces to a log file?
      (logger/debug (async/<! trace-ch))
      (recur)))
  (async/go-loop []
    (apply log-wrapper-fn (async/<! log-ch))
    (recur)))

(defn run-server! []
  (let [timbre-logger (->TimbreLogger)
        log-path (logger/setup timbre-logger)
        db (assoc db/initial-db :log-path log-path)
        db* (atom db)
        server (lsp.server/stdio-server {:trace? false
                                         :in System/in
                                         :out System/out})
        producer (ClojureLspProducer. server db*)
        components {:db* db*
                    :logger timbre-logger
                    :producer producer
                    :server server}]
    (logger/info "[SERVER]" "Starting server...")
    (monitor-server-logs server)
    (nrepl/setup-nrepl db*)
    (spawn-async-tasks! components)
    (lsp.server/start server components)))
