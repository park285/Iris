// thread-markdown-graft.js v3
// 필드명 확정: Z=scope, V0=threadId

"use strict";

var THREAD_ID = "3803466729815130113";
var THREAD_SCOPE = 2;
var MARKER = "FRIDA_GRAFT";

Java.perform(function () {
    console.log("[graft] script loaded");

    var RequestCompanion = Java.use("com.kakao.talk.manager.send.ChatSendingLogRequest$a");
    console.log("[graft] RequestCompanion found");

    var uMethods = RequestCompanion["u"].overloads;
    console.log("[graft] u() overloads: " + uMethods.length);

    for (var i = 0; i < uMethods.length; i++) {
        (function (idx) {
            uMethods[idx].implementation = function () {
                var sendingLog = arguments[1];
                var msg = "";
                var att = "";
                try { msg = sendingLog.f0().toString(); } catch (e) {}
                try { att = sendingLog.t().toString(); } catch (e) {}

                var scope = -1;
                var threadId = null;
                try { scope = sendingLog.m0(); } catch (e) {}
                try { threadId = sendingLog.r0(); } catch (e) {}

                console.log("[graft] submit: msg=" + msg.substring(0, Math.min(msg.length, 60)) +
                    " | scope=" + scope + " | threadId=" + threadId +
                    " | att=" + att.substring(0, Math.min(att.length, 80)));

                if (msg.indexOf(MARKER) !== -1) {
                    console.log("[graft] === MARKER DETECTED - INJECTING ===");

                    var slClass = sendingLog.getClass();
                    try {
                        // Z = scope (int)
                        var scopeField = slClass.getDeclaredField("Z");
                        scopeField.setAccessible(true);
                        scopeField.setInt(sendingLog, THREAD_SCOPE);
                        console.log("[graft] Z (scope) set to " + THREAD_SCOPE);

                        // V0 = threadId (java.lang.Long)
                        var threadField = slClass.getDeclaredField("V0");
                        threadField.setAccessible(true);
                        threadField.set(sendingLog, Java.use("java.lang.Long").valueOf(THREAD_ID));
                        console.log("[graft] V0 (threadId) set to " + THREAD_ID);

                        // 주입 확인
                        var newScope = sendingLog.m0();
                        var newThread = sendingLog.r0();
                        console.log("[graft] VERIFIED: scope=" + newScope + " threadId=" + newThread);
                    } catch (e) {
                        console.log("[graft] INJECTION FAILED: " + e);
                    }
                }

                return uMethods[idx].apply(this, arguments);
            };
        })(i);
    }

    console.log("[graft] hooks installed. Send '" + MARKER + "' via /reply/text-share.");
});
