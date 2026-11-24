package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ZenoraApiClient;
import org.telegram.messenger.ZenoraChatController;
import org.telegram.messenger.ZenoraUserBlockController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Native Telegram-style directory backed exclusively by Zenora's Block API. */
public class ZenoraBlockedUsersActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int PAGE_SIZE = 30;
    private static final int SEARCH_ROW_HEIGHT_DP = 52;

    private static final class BlockedUser {
        final long id;
        final String username;
        final String displayName;
        final String avatarPath;
        final TLRPC.User peer;

        BlockedUser(long id, String username, String displayName, String avatarPath, TLRPC.User peer) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatarPath = avatarPath;
            this.peer = peer;
        }
    }

    private final ArrayList<BlockedUser> users = new ArrayList<>();
    private final Set<Long> pendingUnblocks = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;
    private EditText searchField;
    private LinearLayout searchRow;
    private FrameLayout rootView;

    private String query = "";
    private int nextPage = 1;
    private boolean hasMore = true;
    private boolean loading;
    private boolean initialLoadStarted;
    private boolean glassSearchInsetApplied;
    private long requestGeneration;
    private final Runnable notificationRefreshRunnable = () -> refreshList();

    private final Runnable searchRunnable = () -> {
        String nextQuery = searchField == null ? "" : searchField.getText().toString().trim();
        if (nextQuery.equals(query)) return;
        query = nextQuery;
        requestGeneration++;
        loading = false;
        nextPage = 1;
        hasMore = true;
        users.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) emptyView.showProgress();
        loadPage(true);
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (!ZenoraApiClient.isZenoraSessionEnabled()) return false;
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.blockedUsersDidLoad);
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.updateInterfaces);
        ZenoraUserBlockController.syncEvents(null);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.blockedUsersDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        handler.removeCallbacks(searchRunnable);
        handler.removeCallbacks(notificationRefreshRunnable);
        requestGeneration++;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.BlockedUsers));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        fragmentView = root;
        rootView = root;
        glassSearchInsetApplied = false;

        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        searchRow.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        searchField = new EditText(context);
        searchField.setSingleLine(true);
        searchField.setTextSize(16);
        searchField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        searchField.setHint("Search blocked users");
        searchField.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchField.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0,
                AndroidUtilities.dp(48), 1f));

        ImageView refreshButton = new ImageView(context);
        refreshButton.setImageResource(R.drawable.msg_retry);
        refreshButton.setScaleType(ImageView.ScaleType.CENTER);
        refreshButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN);
        refreshButton.setContentDescription("Refresh blocked users");
        refreshButton.setOnClickListener(v -> refreshList());
        searchRow.addView(refreshButton, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(48), AndroidUtilities.dp(48)));
        searchRow.setClipToPadding(false);
        this.searchRow = searchRow;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setEmptyView(emptyView = new EmptyTextProgressView(context));
        emptyView.setText(LocaleController.getString(R.string.NoBlocked));
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.TOP));
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.TOP));
        // Keep search fixed below Telegram's glass action bar. BaseFragment applies
        // the real system/action-bar insets to the list; onViewCreated aligns this
        // overlay to the resulting content origin instead of guessing with 52dp.
        root.addView(searchRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                SEARCH_ROW_HEIGHT_DP, Gravity.TOP));

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 350L);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= users.size()) return;
            BlockedUser blocked = users.get(position);
            Bundle args = new Bundle();
            args.putLong("user_id", blocked.id);
            presentFragment(new ProfileActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= users.size()) return false;
            showUnblockOptions(users.get(position), view);
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!hasMore || loading || users.isEmpty()) return;
                int last = layoutManager.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 7) loadPage(false);
            }
        });

        // onResume() performs the single initial load. Starting a request here
        // as well caused the first page to be cancelled and fetched twice.
        emptyView.showProgress();
        return fragmentView;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        if (listView == null || searchRow == null || emptyView == null || glassSearchInsetApplied) {
            return;
        }
        int originalTop = listView.getPaddingTop();
        listView.setPadding(listView.getPaddingLeft(),
                originalTop + AndroidUtilities.dp(SEARCH_ROW_HEIGHT_DP),
                listView.getPaddingRight(), listView.getPaddingBottom());
        glassSearchInsetApplied = true;

        View.OnLayoutChangeListener overlayLayoutListener = (v, left, top, right, bottom,
                                                              oldLeft, oldTop, oldRight, oldBottom) ->
                updateGlassOverlayPositions();
        listView.addOnLayoutChangeListener(overlayLayoutListener);
        view.addOnLayoutChangeListener(overlayLayoutListener);
        listView.post(this::updateGlassOverlayPositions);
    }

    /**
     * BaseFragmentSimpleGlass owns the list's status-bar/action-bar/navigation
     * insets. The fixed search row and empty state must follow that exact content
     * rectangle, not a second hard-coded top margin.
     */
    private void updateGlassOverlayPositions() {
        if (rootView == null || listView == null || searchRow == null || emptyView == null
                || rootView.getHeight() <= 0) {
            return;
        }
        int contentTop = listView.getTop() + listView.getPaddingTop();
        int searchTop = Math.max(0, contentTop - AndroidUtilities.dp(SEARCH_ROW_HEIGHT_DP));

        ViewGroup.LayoutParams searchRawParams = searchRow.getLayoutParams();
        if (searchRawParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams searchParams = (FrameLayout.LayoutParams) searchRawParams;
            if (searchParams.topMargin != searchTop) {
                searchParams.topMargin = searchTop;
                searchRow.setLayoutParams(searchParams);
            }
        }

        ViewGroup.LayoutParams emptyRawParams = emptyView.getLayoutParams();
        if (emptyRawParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams emptyParams = (FrameLayout.LayoutParams) emptyRawParams;
            int emptyTop = Math.max(0, contentTop);
            if (emptyParams.topMargin != emptyTop) {
                emptyParams.topMargin = emptyTop;
                emptyView.setLayoutParams(emptyParams);
            }
        }
        if (emptyView.getPaddingBottom() != listView.getPaddingBottom()) {
            emptyView.setPadding(emptyView.getPaddingLeft(), emptyView.getPaddingTop(),
                    emptyView.getPaddingRight(), listView.getPaddingBottom());
        }
    }

    private void refreshList() {
        if (searchField != null) {
            query = searchField.getText().toString().trim();
        }
        nextPage = 1;
        hasMore = true;
        loading = false;
        if (users.isEmpty() && emptyView != null) emptyView.showProgress();
        // Keep the last valid snapshot visible until the replacement page succeeds.
        loadPage(true);
    }

    private void loadPage(boolean reset) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || loading || (!hasMore && !reset)) return;
        if (reset) {
            nextPage = 1;
            hasMore = true;
        }
        loading = true;
        final int requestedPage = nextPage;
        final long generation = ++requestGeneration;
        if (users.isEmpty() && emptyView != null) emptyView.showProgress();

        ZenoraApiClient.getBlockedUsers(requestedPage, PAGE_SIZE, query, (response, error) -> {
            if (generation != requestGeneration || getParentActivity() == null
                    || !ZenoraApiClient.isZenoraSessionEnabled()) return;
            loading = false;
            if (error != null || response == null) {
                FileLog.d("[ZenoraChat] Blocked-users API request failed.");
                if (users.isEmpty() && emptyView != null) {
                    emptyView.setText("Could not load blocked users. Tap refresh to retry.");
                    emptyView.showTextView();
                } else if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Could not refresh blocked users.",
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }

            JSONArray rows = response.optJSONArray("users");
            JSONObject pagination = response.optJSONObject("pagination");
            if (rows == null || pagination == null) {
                if (users.isEmpty() && emptyView != null) {
                    emptyView.setText("The server returned an invalid blocked-users list.");
                    emptyView.showTextView();
                } else {
                    Toast.makeText(getParentActivity(), "The server returned an invalid blocked-users list.",
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }

            ArrayList<BlockedUser> pageUsers = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            if (!reset) {
                for (BlockedUser item : users) seen.add(item.id);
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                long id = row.optLong("id", 0);
                if (id <= 0 || !seen.add(id)) continue;
                String username = row.optString("username", "");
                String displayName = row.optString("display_name", "");
                String avatar = row.optString("avatar_path", "");
                TLRPC.User peer = ZenoraChatController.ensureZenoraUser(
                        currentAccount, id, username, displayName, avatar);
                if (peer == null) continue;
                pageUsers.add(new BlockedUser(id, username, displayName, avatar, peer));
            }

            int previousSize = users.size();
            if (reset) users.clear();
            users.addAll(pageUsers);
            nextPage = requestedPage + 1;
            hasMore = pagination.optBoolean("has_more", false);
            if (adapter != null) {
                if (reset) {
                    adapter.notifyDataSetChanged();
                } else if (!pageUsers.isEmpty()) {
                    adapter.notifyItemRangeInserted(previousSize, pageUsers.size());
                }
            }
            if (emptyView != null && users.isEmpty()) {
                emptyView.setText(LocaleController.getString(R.string.NoBlocked));
                emptyView.showTextView();
            }
        });
    }

    private void showUnblockOptions(BlockedUser user, View anchor) {
        if (getParentActivity() == null || pendingUnblocks.contains(user.id)) return;
        ItemOptions.makeOptions(this, anchor)
                .setScrimViewBackground(new android.graphics.drawable.ColorDrawable(
                        Theme.getColor(Theme.key_windowBackgroundWhite)))
                .add(0, LocaleController.getString(R.string.Unblock), () -> unblock(user))
                .setMinWidth(190)
                .show();
    }

    private void unblock(BlockedUser user) {
        if (pendingUnblocks.contains(user.id)) return;
        pendingUnblocks.add(user.id);
        ZenoraUserBlockController.setBlocked(user.id, false, (state, error) -> {
            pendingUnblocks.remove(user.id);
            if (error != null || state == null || state.blockedByMe != 0) {
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Could not unblock this user.",
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            int removedPosition = -1;
            for (int i = users.size() - 1; i >= 0; i--) {
                if (users.get(i).id == user.id) {
                    users.remove(i);
                    removedPosition = i;
                    break;
                }
            }
            if (adapter != null && removedPosition >= 0) {
                adapter.notifyItemRemoved(removedPosition);
                if (removedPosition < users.size()) adapter.notifyItemChanged(removedPosition);
            }
            if (users.isEmpty() && emptyView != null) {
                emptyView.setText(LocaleController.getString(R.string.NoBlocked));
                emptyView.showTextView();
            }
            // The block controller posts blockedUsersDidLoad after its
            // authoritative state is stored; the debounced observer refreshes
            // the directory once, avoiding a second request here.
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // The event stream is started once when this Fragment is created.
        // Reopening the screen after viewing a profile must not clear/re-fetch
        // the directory or reset its scroll position.
        if (adapter != null && !initialLoadStarted) {
            initialLoadStarted = true;
            refreshList();
        }
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || getParentActivity() == null) return;
        if (id == NotificationCenter.blockedUsersDidLoad) {
            handler.removeCallbacks(notificationRefreshRunnable);
            handler.postDelayed(notificationRefreshRunnable, 120L);
        } else if (id == NotificationCenter.updateInterfaces && adapter != null) {
            // Rebind profile changes locally without forcing another API request.
            adapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return users.size();
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ManageChatUserCell cell = new ManageChatUserCell(context, 7, 6, true);
            cell.setDelegate((view, click) -> {
                if (click && view.getTag() instanceof Long) {
                    long id = (Long) view.getTag();
                    for (BlockedUser user : users) {
                        if (user.id == id) {
                            showUnblockOptions(user, view);
                            break;
                        }
                    }
                }
                return true;
            });
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            BlockedUser blocked = users.get(position);
            ManageChatUserCell cell = (ManageChatUserCell) holder.itemView;
            cell.setTag(blocked.id);
            String subtitle = blocked.username == null || blocked.username.isEmpty()
                    || "deleted_account".equals(blocked.username)
                    ? "" : "@" + blocked.username;
            cell.setData(blocked.peer, null, subtitle, position != users.size() - 1);
        }
    }
}
