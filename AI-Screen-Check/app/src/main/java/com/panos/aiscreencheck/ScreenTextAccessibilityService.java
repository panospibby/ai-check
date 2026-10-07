package com.panos.aiscreencheck;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedHashSet;
import java.util.Set;

public class ScreenTextAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scheduled = false;
    private String lastPackage = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) lastPackage = pkg.toString();
        if (getPackageName().equals(lastPackage)) return;

        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(() -> {
                scheduled = false;
                collectVisibleText();
            }, 180L);
        }
    }

    private void collectVisibleText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        walk(root, parts, 0);
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
            if (sb.length() >= 12000) break;
        }
        String value = sb.length() > 12000 ? sb.substring(0, 12000) : sb.toString();
        VisibleTextStore.update(value, lastPackage);
    }

    private void walk(AccessibilityNodeInfo node, Set<String> out, int depth) {
        if (node == null || depth > 35 || out.size() > 500) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) out.add(text.toString());
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) out.add(desc.toString());
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // No spoken feedback or automation is performed.
    }
}
