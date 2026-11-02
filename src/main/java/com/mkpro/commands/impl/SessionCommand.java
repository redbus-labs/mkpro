package com.mkpro.commands.impl;
import com.google.adk.sessions.*;
import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import java.util.*;
import static com.mkpro.MkPro.*;
public class SessionCommand implements Command {
  @Override public void execute(String[] args, MkProContext ctx) throws Exception {
    String act = (args != null && args.length > 0) ? args[0].toLowerCase() : "list";
    CentralMemory cm = ctx.getCentralMemory() != null ? ctx.getCentralMemory() : CentralMemory.getInstance();
    if ("list".equals(act)) {
      String active = cm.getActiveSessionName();
      System.out.println(ANSI_CYAN + "── Project Sessions ──" + ANSI_RESET);
      cm.getProjectSessions().forEach((k, v) -> System.out.println((k.equals(active) ? "* [ACTIVE] " : "  [      ] ") + k + " (" + v + ")"));
    } else if ("new".equals(act) || "switch".equals(act)) {
      String n = (args.length > 1 && !args[1].isBlank()) ? args[1].trim() : ("new".equals(act) ? "session-" + (System.currentTimeMillis() % 100000) : "default");
      switchSession(n, ctx);
    } else if ("rename".equals(act)) {
      if (args.length >= 3 && cm.renameProjectSession(args[1], args[2])) System.out.println(ANSI_GREEN + "Renamed " + args[1] + " to " + args[2] + ANSI_RESET);
      else System.out.println(ANSI_YELLOW + "Usage: /session rename <old> <new>" + ANSI_RESET);
    } else if ("delete".equals(act)) {
      if (args.length >= 2 && cm.deleteProjectSession(args[1])) {
        System.out.println(ANSI_GREEN + "Deleted session " + args[1] + ANSI_RESET);
        if ("default".equals(cm.getActiveSessionName())) switchSession("default", ctx);
      } else System.out.println(ANSI_YELLOW + "Failed to delete session" + ANSI_RESET);
    } else if (args.length == 1) {
      switchSession(args[0], ctx);
    } else System.out.println(ANSI_YELLOW + "Usage: /session [list|new <name>|switch <name>|rename <old> <new>|delete <name>]" + ANSI_RESET);
  }
  public static Session switchSession(String name, MkProContext ctx) {
    CentralMemory cm = (ctx != null && ctx.getCentralMemory() != null) ? ctx.getCentralMemory() : CentralMemory.getInstance();
    String sid = cm.registerOrActivateSession(name);
    Session s = null;
    if (ctx != null && ctx.getSessionService() != null) {
      SessionKey key = new SessionKey("mkpro", "Coordinator", sid);
      try { s = ctx.getSessionService().getSession(key, GetSessionConfig.builder().build()).blockingGet(); } catch (Exception ignored) {}
      if (s == null) s = ctx.getSessionService().createSession(key, new HashMap<>()).blockingGet();
      ctx.setCurrentSession(s);
    }
    System.out.println(ANSI_GREEN + "✓ Switched to session: " + cm.getActiveSessionName() + " (" + sid + ")" + ANSI_RESET);
    return s;
  }
  @Override public String getName() { return "session"; }
  @Override public String getDescription() { return "Manage named sessions for the current project"; }
}