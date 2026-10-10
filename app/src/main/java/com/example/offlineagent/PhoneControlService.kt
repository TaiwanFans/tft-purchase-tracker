package com.example.offlineagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class PhoneControlService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: PhoneControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun snapshot(maxNodes: Int = 160): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "目前讀不到活動視窗")

        val nodes = JSONArray()
        var count = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val isPassword = node.isPassword
            val safeText = if (isPassword) "[REDACTED]" else node.text?.toString().orEmpty()
            val safeHint = if (isPassword) "[PASSWORD FIELD]" else node.hintText?.toString().orEmpty()

            val useful = safeText.isNotBlank() || safeHint.isNotBlank() ||
                !node.contentDescription.isNullOrBlank() || !node.viewIdResourceName.isNullOrBlank() ||
                node.isClickable || node.isEditable || node.isScrollable

            if (useful) {
                nodes.put(
                    JSONObject()
                        .put("depth", depth)
                        .put("class", node.className?.toString().orEmpty())
                        .put("text", safeText)
                        .put("hint", safeHint)
                        .put("desc", node.contentDescription?.toString().orEmpty())
                        .put("viewId", node.viewIdResourceName.orEmpty())
                        .put("clickable", node.isClickable)
                        .put("editable", node.isEditable)
                        .put("scrollable", node.isScrollable)
                        .put("enabled", node.isEnabled)
                        .put("password", isPassword)
                        .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                )
            }

            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }

        visit(root, 0)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString().orEmpty())
            .put("nodes", nodes)
    }

    fun tap(selector: String): JSONObject {
        if (selector.isBlank()) return JSONObject().put("ok", false).put("error", "selector 不可為空")
        val node = findBestNode(selector)
            ?: return JSONObject().put("ok", false).put("error", "找不到元件：$selector")
        val clickable = findClickableAncestor(node) ?: node

        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return JSONObject().put("ok", true).put("action", "click").put("selector", selector)
        }

        val rect = android.graphics.Rect()
        clickable.getBoundsInScreen(rect)
        return if (rect.width() > 0 && rect.height() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        } else {
            JSONObject().put("ok", false).put("error", "元件存在但無法點擊：$selector")
        }
    }

    fun setText(selector: String, text: String): JSONObject {
        val node = findBestNode(selector)
            ?: return JSONObject().put("ok", false).put("error", "找不到輸入欄：$selector")
        val editable = if (node.isEditable) node else findFirst(node) { it.isEditable }
            ?: return JSONObject().put("ok", false).put("error", "元件不可輸入：$selector")
        if (editable.isPassword) {
            return JSONObject().put("ok", false).put("error", "Agent 不會自動輸入密碼欄位")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return JSONObject()
            .put("ok", editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            .put("action", "set_text")
            .put("selector", selector)
    }

    fun scroll(direction: String): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "目前讀不到活動視窗")
        val scrollable = findFirst(root) { it.isScrollable }
            ?: return JSONObject().put("ok", false).put("error", "目前畫面找不到可滑動區域")
        val action = if (direction.lowercase() in listOf("up", "backward")) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return JSONObject()
            .put("ok", scrollable.performAction(action))
            .put("action", "scroll")
            .put("direction", direction)
    }

    fun global(action: String): JSONObject {
        val id = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return JSONObject().put("ok", false).put("error", "未知 global action：$action")
        }
        return JSONObject().put("ok", performGlobalAction(id)).put("action", action)
    }

    fun tapCoordinates(x: Float, y: Float): JSONObject {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        return JSONObject()
            .put("ok", dispatchGesture(gesture, null, null))
            .put("action", "tap_coordinates")
            .put("x", x)
            .put("y", y)
    }

    private fun findBestNode(selector: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val target = selector.trim().lowercase()
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1

        fun walk(node: AccessibilityNodeInfo) {
            val values = listOf(
                node.text?.toString().orEmpty(),
                node.hintText?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty()
            ).map { it.lowercase() }

            var score = 0
            for (value in values) {
                if (value == target) score = maxOf(score, 100)
                else if (target.isNotBlank() && value.contains(target)) score = maxOf(score, 70)
            }
            if (node.isClickable) score += 10
            if (node.isEditable) score += 10
            if (node.isEnabled) score += 5

            if (score > bestScore) {
                bestScore = score
                best = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }

        walk(root)
        return if (bestScore >= 70) best else null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun findFirst(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirst(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
