// thread-image-graft.js
// Iris thread hint 파일 기반 이미지 thread graft + callingPkg 제거
// 용법: frida -U -p <kakao_pid> -l thread-image-graft.js

"use strict";

var HINT_PATH = "/data/local/tmp/iris-thread-hint.json";

Java.perform(function () {
    function readThreadHint() {
        try {
            var File = Java.use("java.io.File");
            var f = File.$new(HINT_PATH);
            if (!f.exists()) return null;

            var Scanner = Java.use("java.util.Scanner");
            var scanner = Scanner.$new(f);
            var content = scanner.useDelimiter("\\A").next();
            scanner.close();

            f.delete();

            var JSONObject = Java.use("org.json.JSONObject");
            var json = JSONObject.$new(content);
            // Long precision 보호 — 문자열로 보관
            return {
                room: json.getString("room"),
                threadId: json.getString("threadId"),
                threadScope: json.getInt("threadScope"),
            };
        } catch (e) {
            return null;
        }
    }

    function removeCallingPkg(sendingLog) {
        try {
            var cls = sendingLog.getClass();
            var gField = cls.getDeclaredField("G");
            gField.setAccessible(true);
            var att = gField.get(sendingLog);
            if (att === null) return;

            var attStr = att.toString();
            if (attStr.indexOf("callingPkg") !== -1) {
                var JSONObject = Java.use("org.json.JSONObject");
                var json = JSONObject.$new(attStr);
                json.remove("callingPkg");
                var newStr = json.toString();

                // G 필드의 내부 JSON 문자열 필드를 직접 수정
                var attClass = att.getClass();
                var fields = attClass.getDeclaredFields();
                for (var i = 0; i < fields.length; i++) {
                    fields[i].setAccessible(true);
                    if (fields[i].getType().getName() === "java.lang.String") {
                        var val = fields[i].get(att);
                        if (val !== null && val.toString().indexOf("callingPkg") !== -1) {
                            fields[i].set(att, newStr);
                            send("[graft] callingPkg removed");
                            return;
                        }
                    }
                }
            }
        } catch (e) {
            send("[graft] callingPkg removal failed: " + e);
        }
    }

    var uMethods = Java.use("com.kakao.talk.manager.send.ChatSendingLogRequest$a")["u"].overloads;
    for (var i = 0; i < uMethods.length; i++) {
        (function (idx) {
            uMethods[idx].implementation = function () {
                var sl = arguments[1];
                var msgType = "";
                try { msgType = sl.w0().toString(); } catch (e) {}

                if (msgType === "Photo") {
                    var hint = readThreadHint();
                    var chatRoomId = "";
                    try {
                        chatRoomId = Java.use("java.lang.String").valueOf(sl.getChatRoomId());
                    } catch (e) {}

                    removeCallingPkg(sl);

                    if (hint !== null && chatRoomId === hint.room) {
                        send("[graft] injecting thread: room=" + chatRoomId +
                            " threadId=" + hint.threadId + " scope=" + hint.threadScope);

                        var cls = sl.getClass();
                        var scopeField = cls.getDeclaredField("Z");
                        scopeField.setAccessible(true);
                        scopeField.setInt(sl, hint.threadScope);

                        var threadField = cls.getDeclaredField("V0");
                        threadField.setAccessible(true);
                        threadField.set(sl, Java.use("java.lang.Long").valueOf(
                            Java.use("java.lang.String").valueOf(hint.threadId)));

                        send("[graft] injected: scope=" + sl.m0() + " threadId=" + sl.r0());
                    } else if (hint !== null) {
                        send("[graft] hint room mismatch: expected=" + hint.room + " got=" + chatRoomId);
                    }
                }

                return uMethods[idx].apply(this, arguments);
            };
        })(i);
    }

    send("[graft] thread-image-graft hook installed. Hint path: " + HINT_PATH);
});
