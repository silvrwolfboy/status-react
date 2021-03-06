(ns status-im.chat.models.loading
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.ui.screens.chat.state :as chat.state]
            [status-im.data-store.chats :as data-store.chats]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.transport.filters.core :as filters]
            [status-im.chat.models :as chat-model]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.mailserver.core :as mailserver]
            [status-im.utils.config :as config]
            [status-im.utils.datetime :as time]
            [status-im.utils.fx :as fx]
            [status-im.chat.models.message-list :as message-list]
            [taoensso.timbre :as log]))

(defn cursor->clock-value [cursor]
  (js/parseInt (.substring cursor 51 64)))

(defn clock-value->cursor [clock-value]
  (str "000000000000000000000000000000000000000000000000000" clock-value "0x0000000000000000000000000000000000000000000000000000000000000000"))

(fx/defn update-chats-in-app-db
  {:events [:chats-list/load-success]}
  [{:keys [db] :as cofx} new-chats]
  (let [old-chats (:chats db)
        chats (reduce (fn [acc {:keys [chat-id] :as chat}]
                        (assoc acc chat-id
                               (assoc chat
                                      :messages-initialized? false
                                      :messages {})))
                      {}
                      new-chats)
        chats (merge old-chats chats)]
    (fx/merge cofx
              {:db (assoc db :chats chats
                          :chats/loading? false)}
              (filters/load-filters))))

(fx/defn offload-all-messages
  [{:keys [db] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    {:db (update-in db [:chats current-chat-id]
                    assoc
                    :all-loaded? false
                    :cursor nil
                    :messages-initialized? false
                    :messages {}
                    :message-list nil)}))

(fx/defn handle-chat-visibility-changed
  {:events [:chat.ui/message-visibility-changed]}
  [{:keys [db] :as cofx} event]
  (let [viewable-items (.-viewableItems event)
        last-element (aget viewable-items (dec (.-length viewable-items)))]
    (when last-element
      (let [last-element-clock-value (:clock-value (.-item last-element))
            chat-id (:chat-id (.-item last-element))]
        (when (and last-element-clock-value
                   (get-in db [:chats chat-id :messages-initialized?]))
          (let [new-messages (reduce-kv (fn [acc message-id {:keys [clock-value] :as v}]
                                          (if (<= last-element-clock-value clock-value)
                                            (assoc acc message-id v)
                                            acc))
                                        {}
                                        (get-in db [:chats chat-id :messages]))]
            {:db (update-in db [:chats chat-id]
                            assoc
                            :messages new-messages
                            :all-loaded? false
                            :message-list (message-list/add-many nil (vals new-messages))
                            :cursor (clock-value->cursor last-element-clock-value))}))))))

(fx/defn initialize-chats
  "Initialize persisted chats on startup"
  [cofx]
  (data-store.chats/fetch-chats-rpc cofx {:on-success
                                          #(re-frame/dispatch
                                            [:chats-list/load-success %])}))
(fx/defn handle-failed-loading-messages
  {:events [::failed-loading-messages]}
  [{:keys [db]} current-chat-id _ err]
  (log/error "failed loading messages" current-chat-id err)
  (when current-chat-id
    {:db (assoc-in db [:chats current-chat-id :loading-messages?] false)}))

(fx/defn messages-loaded
  "Loads more messages for current chat"
  {:events [::messages-loaded]}
  [{{:keys [current-chat-id] :as db} :db :as cofx}
   chat-id
   session-id
   {:keys [cursor messages]}]
  (when-not (or (nil? current-chat-id)
                (not= chat-id current-chat-id)
                (and (get-in db [:chats current-chat-id :messages-initialized?])
                     (not= session-id
                           (get-in db [:chats current-chat-id :messages-initialized?]))))
    (let [already-loaded-messages    (get-in db [:chats current-chat-id :messages])
          loaded-unviewed-messages-ids (get-in db [:chats current-chat-id :loaded-unviewed-messages-ids] #{})
          ;; We remove those messages that are already loaded, as we might get some duplicates
          {:keys [all-messages
                  new-messages
                  last-clock-value
                  unviewed-message-ids]} (reduce (fn [{:keys [last-clock-value all-messages] :as acc}
                                                      {:keys [clock-value seen message-id] :as message}]
                                                   (cond-> acc
                                                     (or (nil? last-clock-value)
                                                         (> last-clock-value clock-value))
                                                     (assoc :last-clock-value clock-value)

                                                     (not seen)
                                                     (update :unviewed-message-ids conj message-id)

                                                     (nil? (get all-messages message-id))
                                                     (update :new-messages conj message)

                                                     :always
                                                     (update :all-messages assoc message-id message)))
                                                 {:all-messages already-loaded-messages
                                                  :unviewed-message-ids loaded-unviewed-messages-ids
                                                  :new-messages []}
                                                 messages)]
      (fx/merge cofx
                {:db (-> db
                         (assoc-in [:chats current-chat-id :cursor-clock-value] (when (seq cursor) (cursor->clock-value cursor)))
                         (assoc-in [:chats current-chat-id :loaded-unviewed-messages-ids] unviewed-message-ids)
                         (assoc-in [:chats current-chat-id :loading-messages?] false)
                         (assoc-in [:chats current-chat-id :messages] all-messages)
                         (update-in [:chats current-chat-id :message-list] message-list/add-many new-messages)
                         (assoc-in [:chats current-chat-id :cursor] cursor)
                         (assoc-in [:chats current-chat-id :all-loaded?]
                                   (empty? cursor)))}
                (chat-model/mark-messages-seen current-chat-id)))))

(fx/defn load-more-messages
  [{:keys [db] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    (when-let [session-id (get-in db [:chats current-chat-id :messages-initialized?])]
      (when-not (or (get-in db [:chats current-chat-id :all-loaded?])
                    (get-in db [:chats current-chat-id :loading-messages?]))
        (let [cursor (get-in db [:chats current-chat-id :cursor])
              load-messages-fx (data-store.messages/messages-by-chat-id-rpc current-chat-id
                                                                            cursor
                                                                            constants/default-number-of-messages
                                                                            #(re-frame/dispatch [::messages-loaded current-chat-id session-id %])
                                                                            #(re-frame/dispatch [::failed-loading-messages current-chat-id session-id %]))]
          (fx/merge cofx
                    load-messages-fx
                    (mailserver/load-gaps-fx current-chat-id)))))))

(fx/defn load-messages
  [{:keys [db now] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    (when-not (get-in db [:chats current-chat-id :messages-initialized?])
      ; reset chat viewable-items state
      (chat.state/reset)
      (fx/merge cofx
                {:db (assoc-in db [:chats current-chat-id :messages-initialized?] now)}
                (load-more-messages)))))

