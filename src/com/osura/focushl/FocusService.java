package com.osura.focushl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote-control navigation for Apple Music (a phone app), active only while it is in front.
 *
 * Apple Music's own D-pad focus jumps unpredictably, so inside the app this service runs
 * its own selection over every visible clickable item: an arrow key moves to the nearest
 * item in that direction (scrolling the surrounding list at its edge), OK clicks it (or
 * taps a text field so the keyboard opens), and a white outline shows the selection.
 * Holding Back jumps to the tab bar. Every key is left alone while the keyboard shows.
 */
public class FocusService extends AccessibilityService {
    private static final String TARGET = "com.apple.android.music";
    /** A list item partly behind a bar is selectable while at least this much (px) shows. */
    private static final int MIN_VISIBLE = 80;
    /** Items smaller than this (px) are decorations, not navigation targets. */
    private static final int MIN_ITEM = 40;
    /** Wait for the list to settle after a scroll before picking the next item. */
    private static final long SCROLL_SETTLE_MS = 300;
    /** Holding Back for this long jumps to the tab bar; a shorter press is a normal Back. */
    private static final long HOLD_MS = 500;
    private static final String[] TABS = {"Home", "New", "Radio", "Library", "Search"};

    private WindowManager wm;
    private Outline outline;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refresh(); }
    };

    /** The selected item (node identity survives scrolling) and its last known bounds. */
    private AccessibilityNodeInfo selNode;
    private Rect selected;
    /** The selection was picked automatically for a new screen (not moved by the user). */
    private boolean freshPick;
    /** Item selected when the last page scroll started. */
    private AccessibilityNodeInfo scrolledFrom;
    /** List items currently behind a bar or off screen. */
    private List<Item> hiddenContent = new ArrayList<>();
    /** Key code whose DOWN we consumed, so its UP is consumed too. */
    private int swallowedKey = -1;
    private boolean backHeld;
    private boolean backFired;
    private final Runnable backHold = new Runnable() {
        @Override public void run() {
            backFired = true;
            jumpToTabs();
        }
    };

    /** A navigation target: a clickable node, its bounds and its place in drawing order. */
    private static final class Item {
        final AccessibilityNodeInfo node;
        final Rect rect;
        final int order;
        int end;  // last drawing-order index inside this node's subtree
        boolean inScroll;  // part of a scrolling list (vs. a fixed bar or toolbar)
        Rect vis;          // part not hidden behind a bar (whole rect for bar items)
        boolean nested;    // inside another item (e.g. a row's ⋮ button)
        boolean target = true;  // selectable (false: only hides what is beneath it)

        Item(AccessibilityNodeInfo node, Rect rect, int order) {
            this.node = node;
            this.rect = rect;
            this.order = order;
        }
    }

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        outline = new Outline(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.x = 0;
        lp.y = 0;
        wm.addView(outline, lp);
        // Test hook for adb: feeds a key through the same logic as the remote. Only senders
        // holding DUMP (the adb shell) can reach it.
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_TEST_KEY);
        f.addAction(ACTION_TEST_STATE);
        registerReceiver(testHook, f, "android.permission.DUMP", handler, RECEIVER_EXPORTED);
    }

    /** Whether the on-screen keyboard was showing at the last window change. */
    private boolean keyboardWasShowing;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            boolean showing = keyboardShowing();
            if (keyboardWasShowing && !showing) submitTypedText();
            keyboardWasShowing = showing;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            selected = null;  // new screen: start from its natural first item
            selNode = null;
        }
        // Coalesce bursts of events (scrolling fires many) into one redraw.
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 60);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return onBack(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (code == swallowedKey) {
                swallowedKey = -1;
                return true;
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (!handleKeyDown(code)) return false;
        swallowedKey = code;
        return true;
    }

    /** Handles a key press; true when consumed (the app must not see it). */
    private boolean handleKeyDown(int code) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!ok && !arrow) return false;
        AccessibilityNodeInfo root = targetRoot();
        if (root == null || keyboardShowing()) return false;
        if (inPlayer(root)) return playerKey(root, code, ok);
        List<Item> items = itemsIn(root);
        if (items.isEmpty()) return false;
        Item cur = current(items, root);

        if (ok) {
            if (cur.node.isEditable()) {
                if (needsSearchMode(root, cur)) {
                    // Apple's search bar only switches into search mode (live suggestions,
                    // working Enter) on a touch; selecting the Search tab again does the same.
                    AccessibilityNodeInfo tab = tabNamed(root, "Search");
                    if (tab != null && tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        handler.postDelayed(openSearchKeyboard, CLICK_SETTLE_MS);
                        return true;
                    }
                }
                cur.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                cur.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                tap(cur.rect);
                return true;
            }
            boolean done = cur.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    || tap(cur.rect);
            if (done) handler.postDelayed(afterClick, CLICK_SETTLE_MS);
            return done;
        }
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        // Up/Down inside a list stay in the list (scrolling it) until its end is reached.
        // From page content, moves stay in the content (the floating bars are reached at the
        // end of the list); from a bar, Up/Down go to the nearest item of any kind.
        List<Item> pool;
        if (cur.inScroll) pool = reachableContent(items, root, cur.rect);
        else if (vertical) pool = items;
        else pool = barsOnly(items);  // Left/Right along a bar stay in the bars
        Item next = nearest(pool, cur.rect, code);
        if (next != null) {
            select(next, root);
            return true;
        }
        boolean forward = code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        AccessibilityNodeInfo list = scrollableAncestor(cur.node);
        if (list != null && scrollToward(cur.node, code)) {
            final Rect anchor = new Rect(cur.rect);
            final int dir = code;
            scrolledFrom = cur.node;
            handler.postDelayed(new Runnable() {
                @Override public void run() { afterScroll(anchor, dir); }
            }, SCROLL_SETTLE_MS);
            return true;
        }
        // End of the list: carry on into the bars (mini-player, tabs, toolbar).
        if (vertical && cur.inScroll) {
            next = nearest(items, cur.rect, code);
            if (next != null) select(next, root);
        }
        return true;
    }

    private static final long CLICK_SETTLE_MS = 700;
    /** A click that opened something new: start again from that screen's first item. */
    private final Runnable afterClick = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = targetRoot();
            if (root == null || selNode == null) return;
            selNode.refresh();
            boolean stillThere = false;
            for (Item it : itemsIn(root)) {
                if (it.node.equals(selNode) && !isTab(it.node)) stillThere = true;
            }
            if (!stillThere) {
                selNode = null;
                selected = null;
            }
            refresh();
        }
    };

    private static boolean hasAction(AccessibilityNodeInfo n,
            AccessibilityNodeInfo.AccessibilityAction a) {
        return n.getActionList().contains(a);
    }

    /** The full-screen player is open (its sheet covers the screen). */
    private boolean inPlayer(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(
                TARGET + ":id/player_sheet_container")) {
            if (!n.isVisibleToUser()) continue;
            Rect b = boundsOf(n);
            Rect screen = boundsOf(root);
            if ((long) b.width() * b.height() >= (long) screen.width() * screen.height() * 9 / 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * Full-screen player: its playback buttons are hidden from accessibility, so Left/Right
     * skip songs and OK plays/pauses (as media keys). Up selects the top row (artwork,
     * favourite, more options), where Left/Right/OK work on those; Down returns to playback.
     */
    private boolean playerKey(AccessibilityNodeInfo root, int code, boolean ok) {
        List<Item> top = new ArrayList<>();
        for (Item it : itemsIn(root)) {
            if (it.rect.bottom <= boundsOf(root).top + boundsOf(root).height() / 4) top.add(it);
        }
        Item cur = null;
        if (selNode != null) {
            for (Item it : top) if (it.node.equals(selNode)) cur = it;
        }
        if (cur == null) {
            // Playback mode.
            if (code == KeyEvent.KEYCODE_DPAD_UP && !top.isEmpty()) {
                Item first = top.get(0);
                for (Item it : top) if (it.rect.left < first.rect.left) first = it;
                select(first, root);
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else if (ok) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
            return true;
        }
        if (ok) {
            cur.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            selNode = null;
            selected = null;
            playbackMode = true;
            refresh();
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            Item next = nearest(top, cur.rect, code);
            if (next != null) select(next, root);
        }
        return true;
    }

    private void mediaKey(int code) {
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** In the player with nothing selected: playback controls are active, no outline. */
    private boolean playbackMode;

    /** After a scroll, select the item that now lies next to where the selection was. */
    private void afterScroll(Rect anchor, int code) {
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) return;
        List<Item> items = itemsIn(root);
        if (items.isEmpty()) return;
        List<Item> content = contentOnly(items);
        if (content.isEmpty()) content = items;
        // Continue from wherever the previously selected item moved to.
        Rect moved = null;
        List<Item> everything = new ArrayList<>(items);
        everything.addAll(hiddenContent);
        for (Item it : everything) {
            if (it.node.equals(scrolledFrom)) moved = it.rect;
        }
        // Rows under the floating bars count too, so a page scroll never skips them.
        List<Item> reachable = new ArrayList<>(content);
        Rect screen = boundsOf(root);
        for (Item h : hiddenContent) {
            boolean sameKind = Math.abs(h.rect.width() - anchor.width()) <= anchor.width() / 10
                    && Math.abs(h.rect.left - anchor.left) <= 24;
            if (sameKind && Rect.intersects(h.rect, screen)) {
                h.vis = new Rect(h.rect);
                reachable.add(h);
            }
        }
        Item next = moved == null ? null : nearest(reachable, moved, code);
        if (next == null && moved != null) {
            // Nothing further that way: stay on the same item, now scrolled fully into view.
            for (Item it : reachable) if (it.node.equals(scrolledFrom)) next = it;
        }
        if (next == null && moved == null && code == KeyEvent.KEYCODE_DPAD_UP) {
            // Scrolled back a page: the row just above the old top is now the lowest one.
            for (Item it : reachable) {
                if (it.node.equals(scrolledFrom)) continue;
                if (next == null || it.rect.bottom > next.rect.bottom + 10
                        || (Math.abs(it.rect.bottom - next.rect.bottom) <= 10
                            && Math.abs(it.rect.centerX() - anchor.centerX())
                               < Math.abs(next.rect.centerX() - anchor.centerX()))) {
                    next = it;
                }
            }
        }
        if (next == null && moved == null && (code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_LEFT)) {
            // Shelf paged sideways past the old card: take the first card on the side we
            // came from (select() slides it fully into view if it is cut off).
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            for (Item it : reachable) {
                if (it.node.equals(scrolledFrom)) continue;
                if (it.rect.top >= anchor.bottom || it.rect.bottom <= anchor.top) continue;
                if (next == null || (right ? it.rect.left < next.rect.left
                        : it.rect.right > next.rect.right)) {
                    next = it;
                }
            }
        }
        if (next != null) {
            select(next, root);
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            // A page scroll moved everything: continue from the edge we scrolled towards,
            // in the same column as before.
            boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
            for (Item it : content) {
                if (it.node.equals(scrolledFrom)) continue;  // don't land on the same item
                if (next == null) { next = it; continue; }
                long a = down ? it.rect.top : -it.rect.bottom;
                long b = down ? next.rect.top : -next.rect.bottom;
                long ca = Math.abs(it.rect.centerX() - anchor.centerX());
                long cb = Math.abs(next.rect.centerX() - anchor.centerX());
                if (a < b - 20 || (Math.abs(a - b) <= 20 && ca < cb)) next = it;
            }
        } else {
            next = nearest(content, anchor, code);
            if (next == null) next = closest(content, anchor);
        }
        select(next, root);
    }

    /** Selects the item, first scrolling it fully into view if it is cut off at an edge. */
    private void select(Item it, AccessibilityNodeInfo root) {
        freshPick = false;
        selNode = it.node;
        selected = new Rect(it.rect);
        Rect screen = boundsOf(root);
        boolean clipped = it.rect.left <= screen.left + 2 || it.rect.right >= screen.right - 2
                || it.rect.top <= screen.top + 2 || it.rect.bottom >= screen.bottom - 2;
        if (clipped && it.node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId())) {
            handler.postDelayed(refresh, SCROLL_SETTLE_MS);
        }
        refresh();
    }

    /**
     * Back is held back until release: a short press replays a normal Back, holding it
     * past HOLD_MS moves the selection to Apple Music's tab bar instead.
     */
    private boolean onBack(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (backHeld) return true;  // key repeat while held
            if (targetRoot() == null || keyboardShowing()) return false;
            backHeld = true;
            backFired = false;
            handler.postDelayed(backHold, HOLD_MS);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && backHeld) {
            backHeld = false;
            handler.removeCallbacks(backHold);
            if (!backFired) goBack();
            return true;
        }
        return false;
    }

    /** A normal Back; the screen it returns to starts from its first item. */
    private void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                selNode = null;
                selected = null;
                refresh();
            }
        }, CLICK_SETTLE_MS);
    }

    /** Selects the current tab (or the first one) in the tab bar. */
    private boolean jumpToTabs() {
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) return false;
        AccessibilityNodeInfo first = null;
        AccessibilityNodeInfo current = null;
        for (String name : TABS) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(name)) {
                if (n.getContentDescription() == null
                        || !name.contentEquals(n.getContentDescription())
                        || !n.isVisibleToUser()) {
                    continue;
                }
                if (first == null) first = n;
                if (current == null && isSelectedTab(n)) current = n;
            }
        }
        AccessibilityNodeInfo tab = current != null ? current : first;
        if (tab == null) return false;
        freshPick = false;
        selNode = tab;
        selected = boundsOf(tab);
        refresh();
        return true;
    }

    /** Once search mode is on: select its text box and open the keyboard for typing. */
    private final Runnable openSearchKeyboard = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = targetRoot();
            if (root == null) return;
            for (Item it : itemsIn(root)) {
                String id = it.node.getViewIdResourceName();
                if (!it.node.isEditable() || id == null || !id.endsWith(":id/search_src_text")) {
                    continue;
                }
                select(it, root);
                if (!keyboardShowing()) {
                    it.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    it.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                return;
            }
        }
    };

    /**
     * The Search page's own search bar, not yet in search mode. In search mode the bar
     * moves to the top with a back ("Navigate up") button beside the text.
     */
    private boolean needsSearchMode(AccessibilityNodeInfo root, Item field) {
        String id = field.node.getViewIdResourceName();
        if (id == null || !id.endsWith(":id/search_src_text")) return false;
        AccessibilityNodeInfo searchTab = tabNamed(root, "Search");
        if (searchTab == null || !isSelectedTab(searchTab)) return false;
        for (AccessibilityNodeInfo up : root.findAccessibilityNodeInfosByText("Navigate up")) {
            Rect u = boundsOf(up);
            if (u.top < field.rect.bottom && u.bottom > field.rect.top) return false;
        }
        return true;
    }

    private static AccessibilityNodeInfo tabNamed(AccessibilityNodeInfo root, String name) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(name)) {
            if (isTab(n) && name.contentEquals(n.getContentDescription())) return n;
        }
        return null;
    }

    /** Tab-bar buttons; the current tab is not clickable but must stay selectable. */
    private static boolean isTab(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        if (d == null || !n.isFocusable()) return false;
        for (String t : TABS) if (t.contentEquals(d)) return true;
        return false;
    }

    private static boolean isSelectedTab(AccessibilityNodeInfo n) {
        if (n.isSelected()) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && c.isSelected()) return true;
        }
        return false;
    }

    /**
     * The TV keyboard's ✓ key closes the keyboard without sending the "search" action this
     * phone app waits for, so when the keyboard closes over a text field holding text,
     * send that field the Enter (IME action) itself.
     */
    private void submitTypedText() {
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) return;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable() || f.isShowingHintText()) return;
        CharSequence t = f.getText();
        if (t == null || t.toString().trim().isEmpty()) return;
        f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    /** True while an on-screen keyboard window is visible; its keys must not be intercepted. */
    private boolean keyboardShowing() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    /** Dispatches a short touch at the centre of the rect. */
    private boolean tap(Rect b) {
        if (b.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(b.exactCenterX(), b.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    private void refresh() {
        if (outline == null) return;
        AccessibilityNodeInfo root = targetRoot();
        List<Item> items = root == null || keyboardShowing() ? null : itemsIn(root);
        if (items == null || items.isEmpty()) {
            outline.hide();
            return;
        }
        if (inPlayer(root)) {
            // Playback mode unless a top-row button is selected.
            boolean onTop = false;
            if (selNode != null) {
                for (Item it : items) {
                    if (it.node.equals(selNode)
                            && it.rect.bottom <= boundsOf(root).top + boundsOf(root).height() / 4) {
                        onTop = true;
                    }
                }
            }
            if (!onTop) {
                selNode = null;
                selected = null;
                outline.hide();
                return;
            }
        }
        if (selNode == null && selected == null) freshPick = true;
        if (freshPick && selNode != null && !isContent(selNode, items)
                && !contentOnly(items).isEmpty()) {
            selNode = null;  // the page's content has loaded since: start there instead
            selected = null;
        }
        Item cur = current(items, root);
        selNode = cur.node;
        selected = new Rect(cur.rect);
        Rect r = new Rect(cur.vis);
        int[] loc = new int[2];
        outline.getLocationOnScreen(loc);
        r.offset(-loc[0], -loc[1]);
        // Dashed while the selection is (mostly) behind a floating bar.
        outline.show(r, hiddenContent.contains(cur));
    }

    /** Root of the active window when it belongs to Apple Music, else null. */
    private AccessibilityNodeInfo targetRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !TARGET.contentEquals(root.getPackageName())) {
            return null;
        }
        return root;
    }

    /**
     * The selected item: the one matching the stored selection (or nearest to it); with no
     * selection, the app's own focused item if it is one of ours, else the top-left item.
     */
    private Item current(List<Item> items, AccessibilityNodeInfo root) {
        if (selNode != null) {
            for (Item it : items) if (it.node.equals(selNode)) return it;
            for (Item it : hiddenContent) {
                if (it.node.equals(selNode) && it.rect.height() > 0) {
                    it.vis = new Rect(it.rect);
                    return it;
                }
            }
        }
        if (selected != null) return closest(items, selected);
        // Fresh screen: the top-left item of the page content (Apple's own focus position is
        // unreliable), falling back to the bars when the page has no content items.
        List<Item> pool = contentOnly(items);
        if (pool.isEmpty()) pool = items;
        Item best = pool.get(0);
        for (Item it : pool) {
            if (it.vis.top < best.vis.top - 10
                    || (Math.abs(it.vis.top - best.vis.top) <= 10 && it.rect.left < best.rect.left)) {
                best = it;
            }
        }
        return best;
    }

    /** Every visible clickable item on screen that is not covered by something drawn on top. */
    private List<Item> itemsIn(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<Item> all = new ArrayList<>();
        walk(root, all, screen, new int[] {0});
        // Fixed bars (toolbar at the top, mini-player and tabs at the bottom) float over the
        // list; list items reaching under them are left for scrolling to bring into view.
        for (Item a : all) {
            for (Item b : all) {
                if (b != a && b.order < a.order && a.order <= b.end) {
                    a.nested = true;
                    break;
                }
            }
        }
        int topBar = screen.top;
        int bottomBar = screen.bottom;
        for (Item a : all) {
            a.inScroll = scrollableAncestor(a.node) != null;
            if (a.inScroll) continue;
            if (a.rect.centerY() < screen.centerY()) topBar = Math.max(topBar, a.rect.bottom);
            else bottomBar = Math.min(bottomBar, a.rect.top);
        }
        List<Item> visible = new ArrayList<>();
        List<Item> hidden = new ArrayList<>();
        for (Item a : all) {
            if (!a.target) continue;
            if (!a.inScroll) {
                a.vis = a.rect;
                if (!coveredByLater(a, all)) visible.add(a);
                continue;
            }
            if (coveredByLayer(a, all)) continue;
            // List items count while enough of them shows between the bars.
            a.vis = new Rect(a.rect.left, Math.max(a.rect.top, topBar),
                    a.rect.right, Math.min(a.rect.bottom, bottomBar));
            int need = Math.min(MIN_VISIBLE, a.rect.height());
            if (a.vis.height() >= need) visible.add(a); else hidden.add(a);
        }
        hiddenContent = hidden;
        return visible;
    }

    private static void walk(AccessibilityNodeInfo n, List<Item> out, Rect screen, int[] order) {
        int me = order[0]++;
        Item item = null;
        if (n.isVisibleToUser() && (n.isClickable() || n.isEditable() || isTab(n))) {
            Rect b = boundsOf(n);
            if (b.intersect(screen) && b.width() >= MIN_ITEM && b.height() >= MIN_ITEM) {
                item = new Item(n, b, me);
                // Full-screen layers (the player sheet) still hide what is under them, but
                // are not targets themselves; neither are lyric lines or tap-anywhere areas.
                item.target = !isPassive(n, b, screen);
                out.add(item);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) walk(c, out, screen, order);
        }
        if (item != null) item.end = order[0] - 1;
    }

    /**
     * True when the item's centre lies under another item drawn later that is not inside
     * it, e.g. a list row hidden behind the floating mini-player or tab bar.
     */
    private static boolean coveredByLater(Item a, List<Item> all) {
        int cx = a.rect.centerX();
        int cy = a.rect.centerY();
        for (Item b : all) {
            if (b.order <= a.order) continue;
            if (b.order <= a.end) continue;  // b is inside a (e.g. a row's "more" button)
            if (b.rect.contains(cx, cy)) return true;
        }
        return false;
    }

    /**
     * List items for Up/Down: the visible ones plus those laid out under the floating bars
     * (lists here can't scroll by one row from outside, and their page scroll would jump
     * past those rows, so they are selected where they are; the outline shows over the bar).
     */
    private List<Item> reachableContent(List<Item> items, AccessibilityNodeInfo root, Rect from) {
        List<Item> out = contentOnly(items);
        Rect screen = boundsOf(root);
        for (Item h : hiddenContent) {
            // Only the next rows of the same list/column (same width and left edge as the
            // selection); anything else waits for a scroll to bring it properly into view.
            boolean sameKind = Math.abs(h.rect.width() - from.width()) <= from.width() / 10
                    && Math.abs(h.rect.left - from.left) <= 24;
            if (sameKind && Rect.intersects(h.rect, screen)) {
                h.vis = new Rect(h.rect);
                h.vis.intersect(screen);
                out.add(h);
            }
        }
        return out;
    }

    private static List<Item> barsOnly(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (!it.inScroll) out.add(it);
        return out;
    }

    private static boolean isContent(AccessibilityNodeInfo n, List<Item> items) {
        for (Item it : items) if (it.node.equals(n)) return it.inScroll;
        return false;
    }

    private static List<Item> contentOnly(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.inScroll) out.add(it);
        return out;
    }

    /** Hidden under a later full-screen layer (e.g. the page beneath the open player). */
    private static boolean coveredByLayer(Item a, List<Item> all) {
        for (Item b : all) {
            if (b.target || b.order <= a.order || b.order <= a.end) continue;
            if (b.rect.contains(a.rect.centerX(), a.rect.centerY())) return true;
        }
        return false;
    }

    private static boolean isPassive(AccessibilityNodeInfo n, Rect b, Rect screen) {
        String id = n.getViewIdResourceName();
        if (id != null && (id.endsWith(":id/karaoke_lyrics_line_container")
                || id.endsWith(":id/controls_tap_target"))) {
            return true;
        }
        return (long) b.width() * b.height() >= (long) screen.width() * screen.height() * 9 / 10;
    }

    private static Item closest(List<Item> items, Rect prev) {
        Item best = null;
        long bestDist = Long.MAX_VALUE;
        for (Item it : items) {
            if (it.rect.equals(prev)) return it;
            long dx = it.rect.centerX() - prev.centerX();
            long dy = it.rect.centerY() - prev.centerY();
            long d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
        }
        return best;
    }

    /**
     * Nearest item strictly in the arrow's direction from `from`, preferring items in the
     * same row (Left/Right) or column (Up/Down); null if there is none.
     */
    private static Item nearest(List<Item> items, Rect from, int code) {
        boolean horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean backward = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP;
        Item best = null;
        long bestScore = Long.MAX_VALUE;
        for (Item it : items) {
            Rect b = it.rect;
            if (b.equals(from)) continue;
            if (b.contains(from)) {
                // A container of the selection (e.g. the mini-player around its buttons) is
                // reachable by moving Left/Up out of it, never Right/Down (that would loop).
                boolean extends_ = horizontal ? b.left < from.left : b.top < from.top;
                if (!backward || !extends_) continue;
                // Only when no sibling lies that way (siblings score below this offset).
                long score = 500000 + (horizontal ? Math.abs(b.centerX() - from.centerX())
                        : Math.abs(b.centerY() - from.centerY()));
                if (score < bestScore) {
                    bestScore = score;
                    best = it;
                }
                continue;
            }
            // Up/Down move between whole rows and tiles, not the buttons inside them.
            if (!horizontal && it.nested) continue;
            long main;
            long cross;
            boolean overlap;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (b.centerX() <= from.centerX() || b.left < from.centerX()) continue;
                    main = b.left - from.right;
                    cross = Math.abs(b.centerY() - from.centerY());
                    overlap = b.top < from.bottom && b.bottom > from.top;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (b.centerX() >= from.centerX() || b.right > from.centerX()) continue;
                    main = from.left - b.right;
                    cross = Math.abs(b.centerY() - from.centerY());
                    overlap = b.top < from.bottom && b.bottom > from.top;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (b.centerY() <= from.centerY() || b.top < from.centerY()) continue;
                    main = b.top - from.bottom;
                    cross = Math.abs(b.centerX() - from.centerX());
                    overlap = b.left < from.right && b.right > from.left;
                    break;
                default:
                    if (b.centerY() >= from.centerY() || b.bottom > from.centerY()) continue;
                    main = from.top - b.bottom;
                    cross = Math.abs(b.centerX() - from.centerX());
                    overlap = b.left < from.right && b.right > from.left;
                    break;
            }
            // Left/Right stay on the same row; Up/Down may move to items off to the side.
            if (horizontal && !overlap) continue;
            // Lined-up items beat ones off to the side; ties go to the item whose centre is
            // closer along the direction of travel.
            long along = horizontal ? Math.abs(b.centerX() - from.centerX())
                    : Math.abs(b.centerY() - from.centerY());
            long score = 8 * (Math.max(main, 0) + 2 * cross) + along
                    + (overlap ? 0 : 1000000);
            if (score < bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }

    /**
     * Scrolls the nearest enclosing list that moves along the key's axis: a directional
     * scroll action when the list offers one, else forward/backward on a list shaped for
     * that axis (wider than tall for Left/Right, taller than wide for Up/Down).
     */
    private static boolean scrollToward(AccessibilityNodeInfo n, int code) {
        AccessibilityNodeInfo.AccessibilityAction dir;
        boolean horizontal = false;
        boolean forward;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
                horizontal = true; forward = true; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
                horizontal = true; forward = false; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
                forward = true; break;
            default:
                dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
                forward = false; break;
        }
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (!p.isScrollable()) continue;
            List<AccessibilityNodeInfo.AccessibilityAction> acts = p.getActionList();
            boolean directional = false;
            for (AccessibilityNodeInfo.AccessibilityAction a : acts) {
                int id = a.getId();
                if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
                        || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                        || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
                        || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) {
                    directional = true;
                }
            }
            if (directional) {
                if (acts.contains(dir) && p.performAction(dir.getId())) return true;
                continue;  // this list scrolls the other way; try an outer one
            }
            Rect b = boundsOf(p);
            boolean shapedForAxis = horizontal ? b.width() > b.height() : b.height() >= b.width();
            if (shapedForAxis && p.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isScrollable()) return p;
        }
        return null;
    }

    private static Rect boundsOf(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        if (n != null) n.getBoundsInScreen(b);
        return b;
    }

    // ---- adb test hook ----

    private static final String ACTION_TEST_KEY = "com.osura.focushl.TEST_KEY";
    private static final String ACTION_TEST_STATE = "com.osura.focushl.TEST_STATE";
    private final BroadcastReceiver testHook = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_TEST_KEY.equals(i.getAction())) {
                int code = i.getIntExtra("code", 0);
                boolean consumed;
                if (code == -1) consumed = jumpToTabs();
                else if (code == KeyEvent.KEYCODE_BACK) { goBack(); consumed = true; }
                else consumed = handleKeyDown(code);
                setResultCode(consumed ? 1 : 0);
            } else {
                refresh();
                setResultData(stateForTest());
            }
        }
    };

    /** One-line description of the selection, for the adb test harness. */
    private String stateForTest() {
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) return "not-in-apple-music";
        if (inPlayer(root) && selNode == null) return "player:playback-mode";
        List<Item> items = itemsIn(root);
        if (items.isEmpty()) return "no-items";
        Item cur = current(items, root);
        return "items=" + items.size() + " sel=" + cur.rect.toShortString()
                + " label=" + label(cur.node);
    }

    /** First text or description found in the node or its descendants. */
    private static String label(AccessibilityNodeInfo n) {
        if (n == null) return "-";
        if (n.getContentDescription() != null && n.getContentDescription().length() > 0) {
            return n.getContentDescription().toString().replace(' ', '_');
        }
        if (n.getText() != null && n.getText().length() > 0) {
            return n.getText().toString().replace(' ', '_');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            String l = label(n.getChild(i));
            if (!"-".equals(l)) return l;
        }
        return "-";
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(testHook); } catch (IllegalArgumentException ignored) {}
        if (wm != null && outline != null) wm.removeView(outline);
        outline = null;
        super.onDestroy();
    }

    /** Full-screen transparent view that draws a white rounded box with a soft grey rim around one rect. */
    static class Outline extends View {
        private static final float STROKE = 7f;
        private static final float RIM = 3f;
        private static final float RADIUS = 16f;
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF outer = new RectF();
        private boolean visible;

        Outline(Context c) {
            super(c);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(RIM);
            rim.setColor(0x80808080);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(STROKE);
            line.setColor(0xE6FFFFFF);
        }

        void show(Rect r, boolean dashed) {
            float pad = STROKE;
            box.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
            float o = (STROKE + RIM) / 2f;
            outer.set(box.left - o, box.top - o, box.right + o, box.bottom + o);
            line.setPathEffect(dashed ? new DashPathEffect(new float[] {30f, 20f}, 0f) : null);
            rim.setAlpha(dashed ? 0 : 0x80);
            visible = true;
            invalidate();
        }

        void hide() {
            if (!visible) return;
            visible = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!visible) return;
            canvas.drawRoundRect(outer, RADIUS + STROKE, RADIUS + STROKE, rim);
            canvas.drawRoundRect(box, RADIUS, RADIUS, line);
        }
    }
}
