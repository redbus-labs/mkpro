package com.mkpro;

import com.google.adk.sessions.Session;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.BaseSessionService;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionHelper {
    public static String getAppName() {
        return "mkpro-" + System.getProperty("user.name");
    }

    public static String getSessionId(String userName) {
        return getAppName() +":"+userName +":"+ Paths.get("").toAbsolutePath().toString();
    }

    public static ConcurrentMap<String, Object> createDefaultState() {
        ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
        state.put("APP", "MKPRO");
        state.put("ORG", "redBus");
        state.put("username", System.getProperty("user.name"));
        state.put("pwd", Paths.get("").toAbsolutePath().toString());
        return state;
    }

    public static Single<Session> createSession(BaseSessionService service, String userName) {
        // Casting to InMemorySessionService because that's where the specific createSession overload exists
        return service.createSession(getAppName(), userName, createDefaultState(), getSessionId(userName));
    }
}
