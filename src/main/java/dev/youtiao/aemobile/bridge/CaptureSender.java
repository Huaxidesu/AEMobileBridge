package dev.youtiao.aemobile.bridge;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 捕获命令返回给玩家/控制台的聊天，不实际发送。
 *
 * <p>在线玩家：权限、分组委托给真实的 ProxiedPlayer，但 sendMessage 只记录。
 * 离线玩家：用一个虚拟 sender（名字 = 请求里的玩家名），权限按 config 的 offline-permissions 放行。
 */
final class CaptureSender implements CommandSender {

    private final CommandSender delegate;
    private final String name;
    private final Set<String> offlinePermissions;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    CaptureSender(CommandSender delegate, String name, Set<String> offlinePermissions) {
        this.delegate = delegate;
        this.name = name;
        this.offlinePermissions = offlinePermissions;
    }

    List<String> lines() {
        synchronized (lines) {
            return new ArrayList<String>(lines);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void sendMessage(String message) {
        if (message != null && !message.isEmpty()) lines.add(message);
    }

    @Override
    public void sendMessages(String... messages) {
        if (messages == null) return;
        for (String m : messages) sendMessage(m);
    }

    @Override
    public void sendMessage(BaseComponent... components) {
        if (components == null) return;
        for (BaseComponent c : components) sendMessage(c);
    }

    @Override
    public void sendMessage(BaseComponent component) {
        if (component != null) sendMessage(component.toPlainText());
    }

    @Override
    public Collection<String> getGroups() {
        return delegate != null ? delegate.getGroups() : Collections.<String>emptyList();
    }

    @Override
    public void addGroups(String... groups) {
        if (delegate != null) delegate.addGroups(groups);
    }

    @Override
    public void removeGroups(String... groups) {
        if (delegate != null) delegate.removeGroups(groups);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (delegate != null) return delegate.hasPermission(permission);
        if (permission == null) return false;
        for (String allowed : offlinePermissions) {
            if (permission.equals(allowed) || permission.startsWith(allowed + ".")) return true;
        }
        return false;
    }

    @Override
    public void setPermission(String permission, boolean value) {
        if (delegate != null) delegate.setPermission(permission, value);
    }

    @Override
    public Collection<String> getPermissions() {
        return delegate != null ? delegate.getPermissions() : offlinePermissions;
    }
}
