/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.widget;

import android.annotation.ColorInt;
import android.annotation.DimenRes;
import android.app.ActivityManager.StackId;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Application;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.StrictMode;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Filter;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.AdapterView.OnItemClickListener;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import libcore.util.Objects;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

/**
 * A class that describes a view hierarchy that can be displayed in
 * another process. The hierarchy is inflated from a layout resource
 * file, and this class provides some basic operations for modifying
 * the content of the inflated hierarchy.
 */
public class RemoteViews implements Parcelable, Filter {

    private static final String LOG_TAG = "RemoteViews";

    /**
     * The intent extra that contains the appWidgetId.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_APPWIDGET_ID = "remoteAdapterAppWidgetId";

    /**
     * Maximum depth of nested views calls from {@link #addView(int, RemoteViews)} and
     * {@link #RemoteViews(RemoteViews, RemoteViews)}.
     */
    private static final int MAX_NESTED_VIEWS = 10;

    // The unique identifiers for each custom {@link Action}.
    private static final int SET_ON_CLICK_PENDING_INTENT_TAG = 1;
    private static final int REFLECTION_ACTION_TAG = 2;
    private static final int SET_DRAWABLE_PARAMETERS_TAG = 3;
    private static final int VIEW_GROUP_ACTION_ADD_TAG = 4;
    private static final int SET_REFLECTION_ACTION_WITHOUT_PARAMS_TAG = 5;
    private static final int SET_EMPTY_VIEW_ACTION_TAG = 6;
    private static final int VIEW_GROUP_ACTION_REMOVE_TAG = 7;
    private static final int SET_PENDING_INTENT_TEMPLATE_TAG = 8;
    private static final int SET_ON_CLICK_FILL_IN_INTENT_TAG = 9;
    private static final int SET_REMOTE_VIEW_ADAPTER_INTENT_TAG = 10;
    private static final int TEXT_VIEW_DRAWABLE_ACTION_TAG = 11;
    private static final int BITMAP_REFLECTION_ACTION_TAG = 12;
    private static final int TEXT_VIEW_SIZE_ACTION_TAG = 13;
    private static final int VIEW_PADDING_ACTION_TAG = 14;
    private static final int SET_REMOTE_VIEW_ADAPTER_LIST_TAG = 15;
    private static final int TEXT_VIEW_DRAWABLE_COLOR_FILTER_ACTION_TAG = 17;
    private static final int SET_REMOTE_INPUTS_ACTION_TAG = 18;
    private static final int LAYOUT_PARAM_ACTION_TAG = 19;

    /**
     * Application that hosts the remote views.
     *
     * @hide
     */
    private ApplicationInfo mApplication;

    /**
     * The resource ID of the layout file. (Added to the parcel)
     */
    private final int mLayoutId;

    /**
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    private ArrayList<Action> mActions;

    /**
     * A class to keep track of memory usage by this RemoteViews
     */
    private MemoryUsageCounter mMemoryUsageCounter;

    /**
     * Maps bitmaps to unique indicies to avoid Bitmap duplication.
     */
    private BitmapCache mBitmapCache;

    /**
     * Indicates whether or not this RemoteViews object is contained as a child of any other
     * RemoteViews.
     */
    private boolean mIsRoot = true;

    /**
     * Constants to whether or not this RemoteViews is composed of a landscape and portrait
     * RemoteViews.
     */
    private static final int MODE_NORMAL = 0;
    private static final int MODE_HAS_LANDSCAPE_AND_PORTRAIT = 1;

    /**
     * Used in conjunction with the special constructor
     * {@link #RemoteViews(RemoteViews, RemoteViews)} to keep track of the landscape and portrait
     * RemoteViews.
     */
    private RemoteViews mLandscape = null;
    private RemoteViews mPortrait = null;

    /**
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     */
    private boolean mIsWidgetCollectionChild = false;

    private static final OnClickHandler DEFAULT_ON_CLICK_HANDLER = new OnClickHandler();

    private static final Object[] sMethodsLock = new Object[0];
    private static final ArrayMap<Class<? extends View>, ArrayMap<MutablePair<String, Class<?>>, Method>> sMethods =
            new ArrayMap<Class<? extends View>, ArrayMap<MutablePair<String, Class<?>>, Method>>();
    private static final ArrayMap<Method, Method> sAsyncMethods = new ArrayMap<>();

    private static final ThreadLocal<Object[]> sInvokeArgsTls = new ThreadLocal<Object[]>() {
        @Override
        protected Object[] initialValue() {
            return new Object[1];
        }
    };

    /**
     * @hide
     */
    public void setRemoteInputs(int viewId, RemoteInput[] remoteInputs) {
        mActions.add(new SetRemoteInputsAction(viewId, remoteInputs));
    }

    /**
     * Reduces all images and ensures that they are all below the given sizes.
     *
     * @param maxWidth the maximum width allowed
     * @param maxHeight the maximum height allowed
     *
     * @hide
     */
    public void reduceImageSizes(int maxWidth, int maxHeight) {
        ArrayList<Bitmap> cache = mBitmapCache.mBitmaps;
        for (int i = 0; i < cache.size(); i++) {
            Bitmap bitmap = cache.get(i);
            cache.set(i, Icon.scaleDownIfNecessary(bitmap, maxWidth, maxHeight));
        }
    }

    /**
     * Handle with care!
     */
    static class MutablePair<F, S> {
        F first;
        S second;

        MutablePair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MutablePair)) {
                return false;
            }
            MutablePair<?, ?> p = (MutablePair<?, ?>) o;
            return Objects.equal(p.first, first) && Objects.equal(p.second, second);
        }

        @Override
        public int hashCode() {
            return (first == null ? 0 : first.hashCode()) ^ (second == null ? 0 : second.hashCode());
        }
    }

    /**
     * This pair is used to perform lookups in sMethods without causing allocations.
     */
    private final MutablePair<String, Class<?>> mPair =
            new MutablePair<String, Class<?>>(null, null);

    /**
     * This annotation indicates that a subclass of View is allowed to be used
     * with the {@link RemoteViews} mechanism.
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RemoteView {
    }

    /**
     * Exception to send when something goes wrong executing an action
     *
     */
    public static class ActionException extends RuntimeException {
        public ActionException(Exception ex) {
            super(ex);
        }
        public ActionException(String message) {
            super(message);
        }
    }

    /** @hide */
    public static class OnClickHandler {

        private int mEnterAnimationId;

        public boolean onClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent) {
            return onClickHandler(view, pendingIntent, fillInIntent, StackId.INVALID_STACK_ID);
        }

        public boolean onClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent, int launchStackId) {
            try {
                // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                Context context = view.getContext();
                ActivityOptions opts;
                if (mEnterAnimationId != 0) {
                    opts = ActivityOptions.makeCustomAnimation(context, mEnterAnimationId, 0);
                } else {
                    opts = ActivityOptions.makeBasic();
                }

                if (launchStackId != StackId.INVALID_STACK_ID) {
                    opts.setLaunchStackId(launchStackId);
                }
                context.startIntentSender(
                        pendingIntent.getIntentSender(), fillInIntent,
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                        Intent.FLAG_ACTIVITY_NEW_TASK, 0, opts.toBundle());
            } catch (IntentSender.SendIntentException e) {
                android.util.Log.e(LOG_TAG, "Cannot send pending intent: ", e);
                return false;
            } catch (Exception e) {
                android.util.Log.e(LOG_TAG, "Cannot send pending intent due to " +
                        "unknown exception: ", e);
                return false;
            }
            return true;
        }

        public void setEnterAnimationId(int enterAnimationId) {
            mEnterAnimationId = enterAnimationId;
        }
    }

    /**
     * Base class for all actions that can be performed on an
     * inflated view.
     *
     *  SUBCLASSES MUST BE IMMUTABLE SO CLONE WORKS!!!!!
     */
    private abstract static class Action implements Parcelable {
        public abstract void apply(View root, ViewGroup rootParent,
                OnClickHandler handler) throws ActionException;

        public static final int MERGE_REPLACE = 0;
        public static final int MERGE_APPEND = 1;
        public static final int MERGE_IGNORE = 2;

        public int describeContents() {
            return 0;
        }

        /**
         * Overridden by each class to report on it's own memory usage
         */
        public void updateMemoryUsageEstimate(MemoryUsageCounter counter) {
            // We currently only calculate Bitmap memory usage, so by default,
            // don't do anything here
        }

        public void setBitmapCache(BitmapCache bitmapCache) {
            // Do nothing
        }

        public int mergeBehavior() {
            return MERGE_REPLACE;
        }

        public abstract String getActionName();

        public String getUniqueKey() {
            return (getActionName() + viewId);
        }

        /**
         * This is called on the background thread. It should perform any non-ui computations
         * and return the final action which will run on the UI thread.
         * Override this if some of the tasks can be performed async.
         */
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            return this;
        }

        public boolean prefersAsyncApply() {
            return false;
        }

        /**
         * Overridden by subclasses which have (or inherit) an ApplicationInfo instance
         * as member variable
         */
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return true;
        }

        int viewId;
    }

    /**
     * Action class used during async inflation of RemoteViews. Subclasses are not parcelable.
     */
    private static abstract class RuntimeAction extends Action {
        @Override
        public final String getActionName() {
            return "RuntimeAction";
        }

        @Override
        public final void writeToParcel(Parcel dest, int flags) {
            throw new UnsupportedOperationException();
        }
    }

    // Constant used during async execution. It is not parcelable.
    private static final Action ACTION_NOOP = new RuntimeAction() {
        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) { }
    };

    /**
     * Merges the passed RemoteViews actions with this RemoteViews actions according to
     * action-specific merge rules.
     *
     * @param newRv
     *
     * @hide
     */
    public void mergeRemoteViews(RemoteViews newRv) {
        if (newRv == null) return;
        // We first copy the new RemoteViews, as the process of merging modifies the way the actions
        // reference the bitmap cache. We don't want to modify the object as it may need to
        // be merged and applied multiple times.
        RemoteViews copy = newRv.clone();

        HashMap<String, Action> map = new HashMap<String, Action>();
        if (mActions == null) {
            mActions = new ArrayList<Action>();
        }

        int count = mActions.size();
        for (int i = 0; i < count; i++) {
            Action a = mActions.get(i);
            map.put(a.getUniqueKey(), a);
        }

        ArrayList<Action> newActions = copy.mActions;
        if (newActions == null) return;
        count = newActions.size();
        for (int i = 0; i < count; i++) {
            Action a = newActions.get(i);
            String key = newActions.get(i).getUniqueKey();
            int mergeBehavior = newActions.get(i).mergeBehavior();
            if (map.containsKey(key) && mergeBehavior == Action.MERGE_REPLACE) {
                mActions.remove(map.get(key));
                map.remove(key);
            }

            // If the merge behavior is ignore, we don't bother keeping the extra action
            if (mergeBehavior == Action.MERGE_REPLACE || mergeBehavior == Action.MERGE_APPEND) {
                mActions.add(a);
            }
        }

        // Because pruning can remove the need for bitmaps, we reconstruct the bitmap cache
        mBitmapCache = new BitmapCache();
        setBitmapCache(mBitmapCache);
        recalculateMemoryUsage();
    }

    private static class RemoteViewsContextWrapper extends ContextWrapper {
        private final Context mContextForResources;

        RemoteViewsContextWrapper(Context context, Context contextForResources) {
            super(context);
            mContextForResources = contextForResources;
        }

        @Override
        public Resources getResources() {
            return mContextForResources.getResources();
        }

        @Override
        public Resources.Theme getTheme() {
            return mContextForResources.getTheme();
        }

        @Override
        public String getPackageName() {
            return mContextForResources.getPackageName();
        }
    }

    private class SetEmptyView extends Action {
        int viewId;
        int emptyViewId;

        SetEmptyView(int viewId, int emptyViewId) {
            this.viewId = viewId;
            this.emptyViewId = emptyViewId;
        }

        SetEmptyView(Parcel in) {
            this.viewId = in.readInt();
            this.emptyViewId = in.readInt();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(SET_EMPTY_VIEW_ACTION_TAG);
            out.writeInt(this.viewId);
            out.writeInt(this.emptyViewId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (!(view instanceof AdapterView<?>)) return;

            AdapterView<?> adapterView = (AdapterView<?>) view;

            final View emptyView = root.findViewById(emptyViewId);
            if (emptyView == null) return;

            adapterView.setEmptyView(emptyView);
        }

        public String getActionName() {
            return "SetEmptyView";
        }
    }

    private class SetOnClickFillInIntent extends Action {
        public SetOnClickFillInIntent(int id, Intent fillInIntent) {
            this.viewId = id;
            this.fillInIntent = fillInIntent;
        }

        public SetOnClickFillInIntent(Parcel parcel) {
            viewId = parcel.readInt();
            fillInIntent = Intent.CREATOR.createFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_ON_CLICK_FILL_IN_INTENT_TAG);
            dest.writeInt(viewId);
            fillInIntent.writeToParcel(dest, 0 /* no flags */);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (!mIsWidgetCollectionChild) {
                Log.e(LOG_TAG, "The method setOnClickFillInIntent is available " +
                        "only from RemoteViewsFactory (ie. on collection items).");
                return;
            }
            if (target == root) {
                target.setTagInternal(com.android.internal.R.id.fillInIntent, fillInIntent);
            } else if (fillInIntent != null) {
                OnClickListener listener = new OnClickListener() {
                    public void onClick(View v) {
                        // Insure that this view is a child of an AdapterView
                        View parent = (View) v.getParent();
                        // Break the for loop on the first encounter of:
                        //    1) an AdapterView,
                        //    2) an AppWidgetHostView that is not a RemoteViewsFrameLayout, or
                        //    3) a null parent.
                        // 2) and 3) are unexpected and catch the case where a child is not
                        // correctly parented in an AdapterView.
                        while (parent != null && !(parent instanceof AdapterView<?>)
                                && !((parent instanceof AppWidgetHostView) &&
                                    !(parent instanceof RemoteViewsAdapter.RemoteViewsFrameLayout))) {
                            parent = (View) parent.getParent();
                        }

                        if (!(parent instanceof AdapterView<?>)) {
                            // Somehow they've managed to get this far without having
                            // and AdapterView as a parent.
                            Log.e(LOG_TAG, "Collection item doesn't have AdapterView parent");
                            return;
                        }

                        // Insure that a template pending intent has been set on an ancestor
                        if (!(parent.getTag() instanceof PendingIntent)) {
                            Log.e(LOG_TAG, "Attempting setOnClickFillInIntent without" +
                                    " calling setPendingIntentTemplate on parent.");
                            return;
                        }

                        PendingIntent pendingIntent = (PendingIntent) parent.getTag();

                        final Rect rect = getSourceBounds(v);

                        fillInIntent.setSourceBounds(rect);
                        handler.onClickHandler(v, pendingIntent, fillInIntent);
                    }

                };
                target.setOnClickListener(listener);
            }
        }

        public String getActionName() {
            return "SetOnClickFillInIntent";
        }

        Intent fillInIntent;
    }

    private class SetPendingIntentTemplate extends Action {
        public SetPendingIntentTemplate(int id, PendingIntent pendingIntentTemplate) {
            this.viewId = id;
            this.pendingIntentTemplate = pendingIntentTemplate;
        }

        public SetPendingIntentTemplate(Parcel parcel) {
            viewId = parcel.readInt();
            pendingIntentTemplate = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_PENDING_INTENT_TEMPLATE_TAG);
            dest.writeInt(viewId);
            pendingIntentTemplate.writeToParcel(dest, 0 /* no flags */);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view isn't an AdapterView, setting a PendingIntent template doesn't make sense
            if (target instanceof AdapterView<?>) {
                AdapterView<?> av = (AdapterView<?>) target;
                // The PendingIntent template is stored in the view's tag.
                OnItemClickListener listener = new OnItemClickListener() {
                    public void onItemClick(AdapterView<?> parent, View view,
                            int position, long id) {
                        // The view should be a frame layout
                        if (view instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) view;

                            // AdapterViews contain their children in a frame
                            // so we need to go one layer deeper here.
                            if (parent instanceof AdapterViewAnimator) {
                                vg = (ViewGroup) vg.getChildAt(0);
                            }
                            if (vg == null) return;

                            Intent fillInIntent = null;
                            int childCount = vg.getChildCount();
                            for (int i = 0; i < childCount; i++) {
                                Object tag = vg.getChildAt(i).getTag(com.android.internal.R.id.fillInIntent);
                                if (tag instanceof Intent) {
                                    fillInIntent = (Intent) tag;
                                    break;
                                }
                            }
                            if (fillInIntent == null) return;

                            final Rect rect = getSourceBounds(view);

                            final Intent intent = new Intent();
                            intent.setSourceBounds(rect);
                            handler.onClickHandler(view, pendingIntentTemplate, fillInIntent);
                        }
                    }
                };
                av.setOnItemClickListener(listener);
                av.setTag(pendingIntentTemplate);
            } else {
                Log.e(LOG_TAG, "Cannot setPendingIntentTemplate on a view which is not" +
                        "an AdapterView (id: " + viewId + ")");
                return;
            }
        }

        public String getActionName() {
            return "SetPendingIntentTemplate";
        }

        PendingIntent pendingIntentTemplate;
    }

    private class SetRemoteViewsAdapterList extends Action {
        public SetRemoteViewsAdapterList(int id, ArrayList<RemoteViews> list, int viewTypeCount) {
            this.viewId = id;
            this.list = list;
            this.viewTypeCount = viewTypeCount;
        }

        public SetRemoteViewsAdapterList(Parcel parcel) {
            viewId = parcel.readInt();
            viewTypeCount = parcel.readInt();
            int count = parcel.readInt();
            list = new ArrayList<RemoteViews>();

            for (int i = 0; i < count; i++) {
                RemoteViews rv = RemoteViews.CREATOR.createFromParcel(parcel);
                list.add(rv);
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_REMOTE_VIEW_ADAPTER_LIST_TAG);
            dest.writeInt(viewId);
            dest.writeInt(viewTypeCount);

            if (list == null || list.size() == 0) {
                dest.writeInt(0);
            } else {
                int count = list.size();
                dest.writeInt(count);
                for (int i = 0; i < count; i++) {
                    RemoteViews rv = list.get(i);
                    rv.writeToParcel(dest, flags);
                }
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount));
                }
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount));
                }
            }
        }

        public String getActionName() {
            return "SetRemoteViewsAdapterList";
        }

        int viewTypeCount;
        ArrayList<RemoteViews> list;
    }

    private class SetRemoteViewsAdapterIntent extends Action {
        public SetRemoteViewsAdapterIntent(int id, Intent intent) {
            this.viewId = id;
            this.intent = intent;
        }

        public SetRemoteViewsAdapterIntent(Parcel parcel) {
            viewId = parcel.readInt();
            intent = Intent.CREATOR.createFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_REMOTE_VIEW_ADAPTER_INTENT_TAG);
            dest.writeInt(viewId);
            intent.writeToParcel(dest, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            // Embed the AppWidget Id for use in RemoteViewsAdapter when connecting to the intent
            // RemoteViewsService
            AppWidgetHostView host = (AppWidgetHostView) rootParent;
            intent.putExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID, host.getAppWidgetId());
            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsOnClickHandler(handler);
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsOnClickHandler(handler);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                OnClickHandler handler) {
            SetRemoteViewsAdapterIntent copy = new SetRemoteViewsAdapterIntent(viewId, intent);
            copy.isAsync = true;
            return copy;
        }

        public String getActionName() {
            return "SetRemoteViewsAdapterIntent";
        }

        Intent intent;
        boolean isAsync = false;
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     */
    private class SetOnClickPendingIntent extends Action {
        public SetOnClickPendingIntent(int id, PendingIntent pendingIntent) {
            this.viewId = id;
            this.pendingIntent = pendingIntent;
        }

        public SetOnClickPendingIntent(Parcel parcel) {
            viewId = parcel.readInt();

            // We check a flag to determine if the parcel contains a PendingIntent.
            if (parcel.readInt() != 0) {
                pendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_ON_CLICK_PENDING_INTENT_TAG);
            dest.writeInt(viewId);

            // We use a flag to indicate whether the parcel contains a valid object.
            dest.writeInt(pendingIntent != null ? 1 : 0);
            if (pendingIntent != null) {
                pendingIntent.writeToParcel(dest, 0 /* no flags */);
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view is an AdapterView, setting a PendingIntent on click doesn't make much
            // sense, do they mean to set a PendingIntent template for the AdapterView's children?
            if (mIsWidgetCollectionChild) {
                Log.w(LOG_TAG, "Cannot setOnClickPendingIntent for collection item " +
                        "(id: " + viewId + ")");
                ApplicationInfo appInfo = root.getContext().getApplicationInfo();

                // We let this slide for HC and ICS so as to not break compatibility. It should have
                // been disabled from the outset, but was left open by accident.
                if (appInfo != null &&
                        appInfo.targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN) {
                    return;
                }
            }

            // If the pendingIntent is null, we clear the onClickListener
            OnClickListener listener = null;
            if (pendingIntent != null) {
                listener = new OnClickListener() {
                    public void onClick(View v) {
                        // Find target view location in screen coordinates and
                        // fill into PendingIntent before sending.
                        final Rect rect = getSourceBounds(v);

                        final Intent intent = new Intent();
                        intent.setSourceBounds(rect);
                        handler.onClickHandler(v, pendingIntent, intent);
                    }
                };
            }
            target.setOnClickListener(listener);
        }

        public String getActionName() {
            return "SetOnClickPendingIntent";
        }

        PendingIntent pendingIntent;
    }

    private static Rect getSourceBounds(View v) {
        final float appScale = v.getContext().getResources()
                .getCompatibilityInfo().applicationScale;
        final int[] pos = new int[2];
        v.getLocationOnScreen(pos);

        final Rect rect = new Rect();
        rect.left = (int) (pos[0] * appScale + 0.5f);
        rect.top = (int) (pos[1] * appScale + 0.5f);
        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);
        return rect;
    }

    private Method getMethod(View view, String methodName, Class<?> paramType) {
        Method method;
        Class<? extends View> klass = view.getClass();

        synchronized (sMethodsLock) {
            ArrayMap<MutablePair<String, Class<?>>, Method> methods = sMethods.get(klass);
            if (methods == null) {
                methods = new ArrayMap<MutablePair<String, Class<?>>, Method>();
                sMethods.put(klass, methods);
            }

            mPair.first = methodName;
            mPair.second = paramType;

            method = methods.get(mPair);
            if (method == null) {
                try {
                    if (paramType == null) {
                        method = klass.getMethod(methodName);
                    } else {
                        method = klass.getMethod(methodName, paramType);
                    }
                } catch (NoSuchMethodException ex) {
                    throw new ActionException("view: " + klass.getName() + " doesn't have method: "
                            + methodName + getParameters(paramType));
                }

                if (!method.isAnnotationPresent(RemotableViewMethod.class)) {
                    throw new ActionException("view: " + klass.getName()
                            + " can't use method with RemoteViews: "
                            + methodName + getParameters(paramType));
                }

                methods.put(new MutablePair<String, Class<?>>(methodName, paramType), method);
            }
        }

        return method;
    }

    /**
     * @return the async implementation of the provided method.
     */
    private Method getAsyncMethod(Method method) {
        synchronized (sAsyncMethods) {
            int valueIndex = sAsyncMethods.indexOfKey(method);
            if (valueIndex >= 0) {
                return sAsyncMethods.valueAt(valueIndex);
            }

            RemotableViewMethod annotation = method.getAnnotation(RemotableViewMethod.class);
            Method asyncMethod = null;
            if (!annotation.asyncImpl().isEmpty()) {
                try {
                    asyncMethod = method.getDeclaringClass()
                            .getMethod(annotation.asyncImpl(), method.getParameterTypes());
                    if (!asyncMethod.getReturnType().equals(Runnable.class)) {
                        throw new ActionException("Async implementation for " + method.getName() +
                            " does not return a Runnable");
                    }
                } catch (NoSuchMethodException ex) {
                    throw new ActionException("Async implementation declared but not defined for " +
                            method.getName());
                }
            }
            sAsyncMethods.put(method, asyncMethod);
            return asyncMethod;
        }
    }

    private static String getParameters(Class<?> paramType) {
        if (paramType == null) return "()";
        return "(" + paramType + ")";
    }

    private static Object[] wrapArg(Object value) {
        Object[] args = sInvokeArgsTls.get();
        args[0] = value;
        return args;
    }

    /**
     * Equivalent to calling a combination of {@link Drawable#setAlpha(int)},
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * and/or {@link Drawable#setLevel(int)} on the {@link Drawable} of a given view.
     * <p>
     * These operations will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()} by default.  If targetBackground is false,
     * we assume the target is an {@link ImageView} and try applying the operations
     * to {@link ImageView#getDrawable()}.
     * <p>
     * You can omit specific calls by marking their values with null or -1.
     */
    private class SetDrawableParameters extends Action {
        public SetDrawableParameters(int id, boolean targetBackground, int alpha,
                int colorFilter, PorterDuff.Mode mode, int level) {
            this.viewId = id;
            this.targetBackground = targetBackground;
            this.alpha = alpha;
            this.colorFilter = colorFilter;
            this.filterMode = mode;
            this.level = level;
        }

        public SetDrawableParameters(Parcel parcel) {
            viewId = parcel.readInt();
            targetBackground = parcel.readInt() != 0;
            alpha = parcel.readInt();
            colorFilter = parcel.readInt();
            boolean hasMode = parcel.readInt() != 0;
            if (hasMode) {
                filterMode = PorterDuff.Mode.valueOf(parcel.readString());
            } else {
                filterMode = null;
            }
            level = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_DRAWABLE_PARAMETERS_TAG);
            dest.writeInt(viewId);
            dest.writeInt(targetBackground ? 1 : 0);
            dest.writeInt(alpha);
            dest.writeInt(colorFilter);
            if (filterMode != null) {
                dest.writeInt(1);
                dest.writeString(filterMode.toString());
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(level);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = null;
            if (targetBackground) {
                targetDrawable = target.getBackground();
            } else if (target instanceof ImageView) {
                ImageView imageView = (ImageView) target;
                targetDrawable = imageView.getDrawable();
            }

            if (targetDrawable != null) {
                // Perform modifications only if values are set correctly
                if (alpha != -1) {
                    targetDrawable.mutate().setAlpha(alpha);
                }
                if (filterMode != null) {
                    targetDrawable.mutate().setColorFilter(colorFilter, filterMode);
                }
                if (level != -1) {
                    targetDrawable.mutate().setLevel(level);
                }
            }
        }

        public String getActionName() {
            return "SetDrawableParameters";
        }

        boolean targetBackground;
        int alpha;
        int colorFilter;
        PorterDuff.Mode filterMode;
        int level;
    }

    private final class ReflectionActionWithoutParams extends Action {
        final String methodName;

        ReflectionActionWithoutParams(int viewId, String methodName) {
            this.viewId = viewId;
            this.methodName = methodName;
        }

        ReflectionActionWithoutParams(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(SET_REFLECTION_ACTION_WITHOUT_PARAMS_TAG);
            out.writeInt(this.viewId);
            out.writeString(this.methodName);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            try {
                getMethod(view, this.methodName, null).invoke(view);
            } catch (ActionException e) {
                throw e;
            } catch (Exception ex) {
                throw new ActionException(ex);
            }
        }

        public int mergeBehavior() {
            // we don't need to build up showNext or showPrevious calls
            if (methodName.equals("showNext") || methodName.equals("showPrevious")) {
                return MERGE_IGNORE;
            } else {
                return MERGE_REPLACE;
            }
        }

        public String getActionName() {
            return "ReflectionActionWithoutParams";
        }
    }

    private static class BitmapCache {
        ArrayList<Bitmap> mBitmaps;

        public BitmapCache() {
            mBitmaps = new ArrayList<Bitmap>();
        }

        public BitmapCache(Parcel source) {
            int count = source.readInt();
            mBitmaps = new ArrayList<Bitmap>();
            for (int i = 0; i < count; i++) {
                Bitmap b = Bitmap.CREATOR.createFromParcel(source);
                mBitmaps.add(b);
            }
        }

        public int getBitmapId(Bitmap b) {
            if (b == null) {
                return -1;
            } else {
                if (mBitmaps.contains(b)) {
                    return mBitmaps.indexOf(b);
                } else {
                    mBitmaps.add(b);
                    return (mBitmaps.size() - 1);
                }
            }
        }

        public Bitmap getBitmapForId(int id) {
            if (id == -1 || id >= mBitmaps.size()) {
                return null;
            } else {
                return mBitmaps.get(id);
            }
        }

        public void writeBitmapsToParcel(Parcel dest, int flags) {
            int count = mBitmaps.size();
            dest.writeInt(count);
            for (int i = 0; i < count; i++) {
                mBitmaps.get(i).writeToParcel(dest, flags);
            }
        }

        public void assimilate(BitmapCache bitmapCache) {
            ArrayList<Bitmap> bitmapsToBeAdded = bitmapCache.mBitmaps;
            int count = bitmapsToBeAdded.size();
            for (int i = 0; i < count; i++) {
                Bitmap b = bitmapsToBeAdded.get(i);
                if (!mBitmaps.contains(b)) {
                    mBitmaps.add(b);
                }
            }
        }

        public void addBitmapMemory(MemoryUsageCounter memoryCounter) {
            for (int i = 0; i < mBitmaps.size(); i++) {
                memoryCounter.addBitmapMemory(mBitmaps.get(i));
            }
        }

        @Override
        protected BitmapCache clone() {
            BitmapCache bitmapCache = new BitmapCache();
            bitmapCache.mBitmaps.addAll(mBitmaps);
            return bitmapCache;
        }
    }

    private class BitmapReflectionAction extends Action {
        int bitmapId;
        Bitmap bitmap;
        String methodName;

        BitmapReflectionAction(int viewId, String methodName, Bitmap bitmap) {
            this.bitmap = bitmap;
            this.viewId = viewId;
            this.methodName = methodName;
            bitmapId = mBitmapCache.getBitmapId(bitmap);
        }

        BitmapReflectionAction(Parcel in) {
            viewId = in.readInt();
            methodName = in.readString();
            bitmapId = in.readInt();
            bitmap = mBitmapCache.getBitmapForId(bitmapId);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(BITMAP_REFLECTION_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeString(methodName);
            dest.writeInt(bitmapId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent,
                OnClickHandler handler) throws ActionException {
            ReflectionAction ra = new ReflectionAction(viewId, methodName, ReflectionAction.BITMAP,
                    bitmap);
            ra.apply(root, rootParent, handler);
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            bitmapId = bitmapCache.getBitmapId(bitmap);
        }

        public String getActionName() {
            return "BitmapReflectionAction";
        }
    }

    /**
     * Base class for the reflection actions.
     */
    private final class ReflectionAction extends Action {
        static final int BOOLEAN = 1;
        static final int BYTE = 2;
        static final int SHORT = 3;
        static final int INT = 4;
        static final int LONG = 5;
        static final int FLOAT = 6;
        static final int DOUBLE = 7;
        static final int CHAR = 8;
        static final int STRING = 9;
        static final int CHAR_SEQUENCE = 10;
        static final int URI = 11;
        // BITMAP actions are never stored in the list of actions. They are only used locally
        // to implement BitmapReflectionAction, which eliminates duplicates using BitmapCache.
        static final int BITMAP = 12;
        static final int BUNDLE = 13;
        static final int INTENT = 14;
        static final int COLOR_STATE_LIST = 15;
        static final int ICON = 16;

        String methodName;
        int type;
        Object value;

        ReflectionAction(int viewId, String methodName, int type, Object value) {
            this.viewId = viewId;
            this.methodName = methodName;
            this.type = type;
            this.value = value;
        }

        ReflectionAction(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString();
            this.type = in.readInt();
            //noinspection ConstantIfStatement
            if (false) {
                Log.d(LOG_TAG, "read viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }

            // For some values that may have been null, we first check a flag to see if they were
            // written to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    this.value = in.readInt() != 0;
                    break;
                case BYTE:
                    this.value = in.readByte();
                    break;
                case SHORT:
                    this.value = (short)in.readInt();
                    break;
                case INT:
                    this.value = in.readInt();
                    break;
                case LONG:
                    this.value = in.readLong();
                    break;
                case FLOAT:
                    this.value = in.readFloat();
                    break;
                case DOUBLE:
                    this.value = in.readDouble();
                    break;
                case CHAR:
                    this.value = (char)in.readInt();
                    break;
                case STRING:
                    this.value = in.readString();
                    break;
                case CHAR_SEQUENCE:
                    this.value = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    break;
                case URI:
                    if (in.readInt() != 0) {
                        this.value = Uri.CREATOR.createFromParcel(in);
                    }
                    break;
                case BITMAP:
                    if (in.readInt() != 0) {
                        this.value = Bitmap.CREATOR.createFromParcel(in);
                    }
                    break;
                case BUNDLE:
                    this.value = in.readBundle();
                    break;
                case INTENT:
                    if (in.readInt() != 0) {
                        this.value = Intent.CREATOR.createFromParcel(in);
                    }
                    break;
                case COLOR_STATE_LIST:
                    if (in.readInt() != 0) {
                        this.value = ColorStateList.CREATOR.createFromParcel(in);
                    }
                    break;
                case ICON:
                    if (in.readInt() != 0) {
                        this.value = Icon.CREATOR.createFromParcel(in);
                    }
                default:
                    break;
            }
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(REFLECTION_ACTION_TAG);
            out.writeInt(this.viewId);
            out.writeString(this.methodName);
            out.writeInt(this.type);
            //noinspection ConstantIfStatement
            if (false) {
                Log.d(LOG_TAG, "write viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }

            // For some values which are null, we record an integer flag to indicate whether
            // we have written a valid value to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    out.writeInt((Boolean) this.value ? 1 : 0);
                    break;
                case BYTE:
                    out.writeByte((Byte) this.value);
                    break;
                case SHORT:
                    out.writeInt((Short) this.value);
                    break;
                case INT:
                    out.writeInt((Integer) this.value);
                    break;
                case LONG:
                    out.writeLong((Long) this.value);
                    break;
                case FLOAT:
                    out.writeFloat((Float) this.value);
                    break;
                case DOUBLE:
                    out.writeDouble((Double) this.value);
                    break;
                case CHAR:
                    out.writeInt((int)((Character)this.value).charValue());
                    break;
                case STRING:
                    out.writeString((String)this.value);
                    break;
                case CHAR_SEQUENCE:
                    TextUtils.writeToParcel((CharSequence)this.value, out, flags);
                    break;
                case URI:
                    out.writeInt(this.value != null ? 1 : 0);
                    if (this.value != null) {
                        ((Uri)this.value).writeToParcel(out, flags);
                    }
                    break;
                case BITMAP:
                    out.writeInt(this.value != null ? 1 : 0);
                    if (this.value != null) {
                        ((Bitmap)this.value).writeToParcel(out, flags);
                    }
                    break;
                case BUNDLE:
                    out.writeBundle((Bundle) this.value);
                    break;
                case INTENT:
                    out.writeInt(this.value != null ? 1 : 0);
                    if (this.value != null) {
                        ((Intent)this.value).writeToParcel(out, flags);
                    }
                    break;
                case COLOR_STATE_LIST:
                    out.writeInt(this.value != null ? 1 : 0);
                    if (this.value != null) {
                        ((ColorStateList)this.value).writeToParcel(out, flags);
                    }
                    break;
                case ICON:
                    out.writeInt(this.value != null ? 1 : 0);
                    if (this.value != null) {
                        ((Icon)this.value).writeToParcel(out, flags);
                    }
                    break;
                default:
                    break;
            }
        }

        private Class<?> getParameterType() {
            switch (this.type) {
                case BOOLEAN:
                    return boolean.class;
                case BYTE:
                    return byte.class;
                case SHORT:
                    return short.class;
                case INT:
                    return int.class;
                case LONG:
                    return long.class;
                case FLOAT:
                    return float.class;
                case DOUBLE:
                    return double.class;
                case CHAR:
                    return char.class;
                case STRING:
                    return String.class;
                case CHAR_SEQUENCE:
                    return CharSequence.class;
                case URI:
                    return Uri.class;
                case BITMAP:
                    return Bitmap.class;
                case BUNDLE:
                    return Bundle.class;
                case INTENT:
                    return Intent.class;
                case COLOR_STATE_LIST:
                    return ColorStateList.class;
                case ICON:
                    return Icon.class;
                default:
                    return null;
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            Class<?> param = getParameterType();
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }

            try {
                getMethod(view, this.methodName, param).invoke(view, wrapArg(this.value));
            } catch (ActionException e) {
                throw e;
            } catch (Exception ex) {
                throw new ActionException(ex);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return ACTION_NOOP;

            Class<?> param = getParameterType();
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }

            try {
                Method method = getMethod(view, this.methodName, param);
                Method asyncMethod = getAsyncMethod(method);

                if (asyncMethod != null) {
                    Runnable endAction = (Runnable) asyncMethod.invoke(view, wrapArg(this.value));
                    if (endAction == null) {
                        return ACTION_NOOP;
                    } else {
                        // Special case view stub
                        if (endAction instanceof ViewStub.ViewReplaceRunnable) {
                            root.createTree();
                            // Replace child tree
                            root.findViewTreeById(viewId).replaceView(
                                    ((ViewStub.ViewReplaceRunnable) endAction).view);
                        }
                        return new RunnableAction(endAction);
                    }
                }
            } catch (ActionException e) {
                throw e;
            } catch (Exception ex) {
                throw new ActionException(ex);
            }

            return this;
        }

        public int mergeBehavior() {
            // smoothScrollBy is cumulative, everything else overwites.
            if (methodName.equals("smoothScrollBy")) {
                return MERGE_APPEND;
            } else {
                return MERGE_REPLACE;
            }
        }

        public String getActionName() {
            // Each type of reflection action corresponds to a setter, so each should be seen as
            // unique from the standpoint of merging.
            return "ReflectionAction" + this.methodName + this.type;
        }

        @Override
        public boolean prefersAsyncApply() {
            return this.type == URI || this.type == ICON;
        }
    }

    /**
     * This is only used for async execution of actions and it not parcelable.
     */
    private static final class RunnableAction extends RuntimeAction {
        private final Runnable mRunnable;

        RunnableAction(Runnable r) {
            mRunnable = r;
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            mRunnable.run();
        }
    }

    private void configureRemoteViewsAsChild(RemoteViews rv) {
        mBitmapCache.assimilate(rv.mBitmapCache);
        rv.setBitmapCache(mBitmapCache);
        rv.setNotRoot();
    }

    void setNotRoot() {
        mIsRoot = false;
    }

    /**
     * ViewGroup methods that are related to adding Views.
     */
    private class ViewGroupActionAdd extends Action {
        private RemoteViews mNestedViews;
        private int mIndex;

        ViewGroupActionAdd(int viewId, RemoteViews nestedViews) {
            this(viewId, nestedViews, -1 /* index */);
        }

        ViewGroupActionAdd(int viewId, RemoteViews nestedViews, int index) {
            this.viewId = viewId;
            mNestedViews = nestedViews;
            mIndex = index;
            if (nestedViews != null) {
                configureRemoteViewsAsChild(nestedViews);
            }
        }

        ViewGroupActionAdd(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info,
                int depth) {
            viewId = parcel.readInt();
            mIndex = parcel.readInt();
            mNestedViews = new RemoteViews(parcel, bitmapCache, info, depth);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(VIEW_GROUP_ACTION_ADD_TAG);
            dest.writeInt(viewId);
            dest.writeInt(mIndex);
            mNestedViews.writeToParcel(dest, flags);
        }

        @Override
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return mNestedViews.mApplication.packageName.equals(parentInfo.packageName)
                    && mNestedViews.mApplication.uid == parentInfo.uid;
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final Context context = root.getContext();
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            // Inflate nested views and add as children
            target.addView(mNestedViews.apply(context, target, handler), mIndex);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);
            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }
            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            // Inflate nested views and perform all the async tasks for the child remoteView.
            final Context context = root.mRoot.getContext();
            final AsyncApplyTask task = mNestedViews.getAsyncApplyTask(
                    context, targetVg, null, handler);
            final ViewTree tree = task.doInBackground();

            if (tree == null) {
                throw new ActionException(task.mError);
            }

            // Update the global view tree, so that next call to findViewTreeById
            // goes through the subtree as well.
            target.addChild(tree, mIndex);

            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, OnClickHandler handler)
                        throws ActionException {
                    task.onPostExecute(tree);
                    targetVg.addView(task.mResult, mIndex);
                }
            };
        }

        @Override
        public void updateMemoryUsageEstimate(MemoryUsageCounter counter) {
            counter.increment(mNestedViews.estimateMemoryUsage());
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            mNestedViews.setBitmapCache(bitmapCache);
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }

        @Override
        public boolean prefersAsyncApply() {
            return mNestedViews.prefersAsyncApply();
        }


        @Override
        public String getActionName() {
            return "ViewGroupActionAdd";
        }
    }

    /**
     * ViewGroup methods related to removing child views.
     */
    private class ViewGroupActionRemove extends Action {
        /**
         * Id that indicates that all child views of the affected ViewGroup should be removed.
         *
         * <p>Using -2 because the default id is -1. This avoids accidentally matching that.
         */
        private static final int REMOVE_ALL_VIEWS_ID = -2;

        private int mViewIdToKeep;

        ViewGroupActionRemove(int viewId) {
            this(viewId, REMOVE_ALL_VIEWS_ID);
        }

        ViewGroupActionRemove(int viewId, int viewIdToKeep) {
            this.viewId = viewId;
            mViewIdToKeep = viewIdToKeep;
        }

        ViewGroupActionRemove(Parcel parcel) {
            viewId = parcel.readInt();
            mViewIdToKeep = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(VIEW_GROUP_ACTION_REMOVE_TAG);
            dest.writeInt(viewId);
            dest.writeInt(mViewIdToKeep);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                target.removeAllViews();
                return;
            }

            removeAllViewsExceptIdToKeep(target);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);

            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }

            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            // Clear all children when nested views omitted
            target.mChildren = null;
            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, OnClickHandler handler)
                        throws ActionException {
                    if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                        targetVg.removeAllViews();
                        return;
                    }

                    removeAllViewsExceptIdToKeep(targetVg);
                }
            };
        }

        /**
         * Iterates through the children in the given ViewGroup and removes all the views that
         * do not have an id of {@link #mViewIdToKeep}.
         */
        private void removeAllViewsExceptIdToKeep(ViewGroup viewGroup) {
            // Otherwise, remove all the views that do not match the id to keep.
            int index = viewGroup.getChildCount() - 1;
            while (index >= 0) {
                if (viewGroup.getChildAt(index).getId() != mViewIdToKeep) {
                    viewGroup.removeViewAt(index);
                }
                index--;
            }
        }

        @Override
        public String getActionName() {
            return "ViewGroupActionRemove";
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }
    }

    /**
     * Helper action to set compound drawables on a TextView. Supports relative
     * (s/t/e/b) or cardinal (l/t/r/b) arrangement.
     */
    private class TextViewDrawableAction extends Action {
        public TextViewDrawableAction(int viewId, boolean isRelative, int d1, int d2, int d3, int d4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = false;
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.d4 = d4;
        }

        public TextViewDrawableAction(int viewId, boolean isRelative,
                Icon i1, Icon i2, Icon i3, Icon i4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = true;
            this.i1 = i1;
            this.i2 = i2;
            this.i3 = i3;
            this.i4 = i4;
        }

        public TextViewDrawableAction(Parcel parcel) {
            viewId = parcel.readInt();
            isRelative = (parcel.readInt() != 0);
            useIcons = (parcel.readInt() != 0);
            if (useIcons) {
                if (parcel.readInt() != 0) {
                    i1 = Icon.CREATOR.createFromParcel(parcel);
                }
                if (parcel.readInt() != 0) {
                    i2 = Icon.CREATOR.createFromParcel(parcel);
                }
                if (parcel.readInt() != 0) {
                    i3 = Icon.CREATOR.createFromParcel(parcel);
                }
                if (parcel.readInt() != 0) {
                    i4 = Icon.CREATOR.createFromParcel(parcel);
                }
            } else {
                d1 = parcel.readInt();
                d2 = parcel.readInt();
                d3 = parcel.readInt();
                d4 = parcel.readInt();
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TEXT_VIEW_DRAWABLE_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeInt(isRelative ? 1 : 0);
            dest.writeInt(useIcons ? 1 : 0);
            if (useIcons) {
                if (i1 != null) {
                    dest.writeInt(1);
                    i1.writeToParcel(dest, 0);
                } else {
                    dest.writeInt(0);
                }
                if (i2 != null) {
                    dest.writeInt(1);
                    i2.writeToParcel(dest, 0);
                } else {
                    dest.writeInt(0);
                }
                if (i3 != null) {
                    dest.writeInt(1);
                    i3.writeToParcel(dest, 0);
                } else {
                    dest.writeInt(0);
                }
                if (i4 != null) {
                    dest.writeInt(1);
                    i4.writeToParcel(dest, 0);
                } else {
                    dest.writeInt(0);
                }
            } else {
                dest.writeInt(d1);
                dest.writeInt(d2);
                dest.writeInt(d3);
                dest.writeInt(d4);
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            if (drawablesLoaded) {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else if (useIcons) {
                final Context ctx = target.getContext();
                final Drawable id1 = i1 == null ? null : i1.loadDrawable(ctx);
                final Drawable id2 = i2 == null ? null : i2.loadDrawable(ctx);
                final Drawable id3 = i3 == null ? null : i3.loadDrawable(ctx);
                final Drawable id4 = i4 == null ? null : i4.loadDrawable(ctx);
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(d1, d2, d3, d4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(d1, d2, d3, d4);
                }
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return ACTION_NOOP;

            TextViewDrawableAction copy = useIcons ?
                    new TextViewDrawableAction(viewId, isRelative, i1, i2, i3, i4) :
                    new TextViewDrawableAction(viewId, isRelative, d1, d2, d3, d4);

            // Load the drawables on the background thread.
            copy.drawablesLoaded = true;
            final Context ctx = target.getContext();

            if (useIcons) {
                copy.id1 = i1 == null ? null : i1.loadDrawable(ctx);
                copy.id2 = i2 == null ? null : i2.loadDrawable(ctx);
                copy.id3 = i3 == null ? null : i3.loadDrawable(ctx);
                copy.id4 = i4 == null ? null : i4.loadDrawable(ctx);
            } else {
                copy.id1 = d1 == 0 ? null : ctx.getDrawable(d1);
                copy.id2 = d2 == 0 ? null : ctx.getDrawable(d2);
                copy.id3 = d3 == 0 ? null : ctx.getDrawable(d3);
                copy.id4 = d4 == 0 ? null : ctx.getDrawable(d4);
            }
            return copy;
        }

        @Override
        public boolean prefersAsyncApply() {
            return useIcons;
        }

        public String getActionName() {
            return "TextViewDrawableAction";
        }

        boolean isRelative = false;
        boolean useIcons = false;
        int d1, d2, d3, d4;
        Icon i1, i2, i3, i4;

        boolean drawablesLoaded = false;
        Drawable id1, id2, id3, id4;
    }

    /**
     * Helper action to set text size on a TextView in any supported units.
     */
    private class TextViewSizeAction extends Action {
        public TextViewSizeAction(int viewId, int units, float size) {
            this.viewId = viewId;
            this.units = units;
            this.size = size;
        }

        public TextViewSizeAction(Parcel parcel) {
            viewId = parcel.readInt();
            units = parcel.readInt();
            size  = parcel.readFloat();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TEXT_VIEW_SIZE_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeInt(units);
            dest.writeFloat(size);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            target.setTextSize(units, size);
        }

        public String getActionName() {
            return "TextViewSizeAction";
        }

        int units;
        float size;
    }

    /**
     * Helper action to set padding on a View.
     */
    private class ViewPaddingAction extends Action {
        public ViewPaddingAction(int viewId, int left, int top, int right, int bottom) {
            this.viewId = viewId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public ViewPaddingAction(Parcel parcel) {
            viewId = parcel.readInt();
            left = parcel.readInt();
            top = parcel.readInt();
            right = parcel.readInt();
            bottom = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(VIEW_PADDING_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeInt(left);
            dest.writeInt(top);
            dest.writeInt(right);
            dest.writeInt(bottom);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;
            target.setPadding(left, top, right, bottom);
        }

        public String getActionName() {
            return "ViewPaddingAction";
        }

        int left, top, right, bottom;
    }

    /**
     * Helper action to set layout params on a View.
     */
    private static class LayoutParamAction extends Action {

        /** Set marginEnd */
        public static final int LAYOUT_MARGIN_END_DIMEN = 1;
        /** Set width */
        public static final int LAYOUT_WIDTH = 2;
        public static final int LAYOUT_MARGIN_BOTTOM_DIMEN = 3;

        /**
         * @param viewId ID of the view alter
         * @param property which layout parameter to alter
         * @param value new value of the layout parameter
         */
        public LayoutParamAction(int viewId, int property, int value) {
            this.viewId = viewId;
            this.property = property;
            this.value = value;
        }

        public LayoutParamAction(Parcel parcel) {
            viewId = parcel.readInt();
            property = parcel.readInt();
            value = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(LAYOUT_PARAM_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeInt(property);
            dest.writeInt(value);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) {
                return;
            }
            ViewGroup.LayoutParams layoutParams = target.getLayoutParams();
            if (layoutParams == null) {
                return;
            }
            switch (property) {
                case LAYOUT_MARGIN_END_DIMEN:
                    if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                        int resolved = resolveDimenPixelOffset(target, value);
                        ((ViewGroup.MarginLayoutParams) layoutParams).setMarginEnd(resolved);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_BOTTOM_DIMEN:
                    if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                        int resolved = resolveDimenPixelOffset(target, value);
                        ((ViewGroup.MarginLayoutParams) layoutParams).bottomMargin = resolved;
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_WIDTH:
                    layoutParams.width = value;
                    target.setLayoutParams(layoutParams);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown property " + property);
            }
        }

        private static int resolveDimenPixelOffset(View target, int value) {
            if (value == 0) {
                return 0;
            }
            return target.getContext().getResources().getDimensionPixelOffset(value);
        }

        public String getActionName() {
            return "LayoutParamAction" + property + ".";
        }

        int property;
        int value;
    }

    /**
     * Helper action to set a color filter on a compound drawable on a TextView. Supports relative
     * (s/t/e/b) or cardinal (l/t/r/b) arrangement.
     */
    private class TextViewDrawableColorFilterAction extends Action {
        public TextViewDrawableColorFilterAction(int viewId, boolean isRelative, int index,
                int color, PorterDuff.Mode mode) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.index = index;
            this.color = color;
            this.mode = mode;
        }

        public TextViewDrawableColorFilterAction(Parcel parcel) {
            viewId = parcel.readInt();
            isRelative = (parcel.readInt() != 0);
            index = parcel.readInt();
            color = parcel.readInt();
            mode = readPorterDuffMode(parcel);
        }

        private PorterDuff.Mode readPorterDuffMode(Parcel parcel) {
            int mode = parcel.readInt();
            if (mode >= 0 && mode < PorterDuff.Mode.values().length) {
                return PorterDuff.Mode.values()[mode];
            } else {
                return PorterDuff.Mode.CLEAR;
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(TEXT_VIEW_DRAWABLE_COLOR_FILTER_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeInt(isRelative ? 1 : 0);
            dest.writeInt(index);
            dest.writeInt(color);
            dest.writeInt(mode.ordinal());
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            Drawable[] drawables = isRelative
                    ? target.getCompoundDrawablesRelative()
                    : target.getCompoundDrawables();
            if (index < 0 || index >= 4) {
                throw new IllegalStateException("index must be in range [0, 3].");
            }
            Drawable d = drawables[index];
            if (d != null) {
                d.mutate();
                d.setColorFilter(color, mode);
            }
        }

        public String getActionName() {
            return "TextViewDrawableColorFilterAction";
        }

        final boolean isRelative;
        final int index;
        final int color;
        final PorterDuff.Mode mode;
    }

    /**
     * Helper action to add a view tag with RemoteInputs.
     */
    private class SetRemoteInputsAction extends Action {

        public SetRemoteInputsAction(int viewId, RemoteInput[] remoteInputs) {
            this.viewId = viewId;
            this.remoteInputs = remoteInputs;
        }

        public SetRemoteInputsAction(Parcel parcel) {
            viewId = parcel.readInt();
            remoteInputs = parcel.createTypedArray(RemoteInput.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(SET_REMOTE_INPUTS_ACTION_TAG);
            dest.writeInt(viewId);
            dest.writeTypedArray(remoteInputs, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            target.setTagInternal(R.id.remote_input_tag, remoteInputs);
        }

        public String getActionName() {
            return "SetRemoteInputsAction";
        }

        final Parcelable[] remoteInputs;
    }

    /**
     * Simple class used to keep track of memory usage in a RemoteViews.
     *
     */
    private class MemoryUsageCounter {
        public void clear() {
            mMemoryUsage = 0;
        }

        public void increment(int numBytes) {
            mMemoryUsage += numBytes;
        }

        public int getMemoryUsage() {
            return mMemoryUsage;
        }

        public void addBitmapMemory(Bitmap b) {
            increment(b.getAllocationByteCount());
        }

        int mMemoryUsage;
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource
     * @param layoutId The id of the layout resource
     */
    public RemoteViews(String packageName, int layoutId) {
        this(getApplicationInfo(packageName, UserHandle.myUserId()), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource.
     * @param userId The user under which the package is running.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    public RemoteViews(String packageName, int userId, int layoutId) {
        this(getApplicationInfo(packageName, userId), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param application The application whose content is shown by the views.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    protected RemoteViews(ApplicationInfo application, int layoutId) {
        mApplication = application;
        mLayoutId = layoutId;
        mBitmapCache = new BitmapCache();
        // setup the memory usage statistics
        mMemoryUsageCounter = new MemoryUsageCounter();
        recalculateMemoryUsage();
    }

    private boolean hasLandscapeAndPortraitLayouts() {
        return (mLandscape != null) && (mPortrait != null);
    }

    /**
     * Create a new RemoteViews object that will inflate as the specified
     * landspace or portrait RemoteViews, depending on the current configuration.
     *
     * @param landscape The RemoteViews to inflate in landscape configuration
     * @param portrait The RemoteViews to inflate in portrait configuration
     */
    public RemoteViews(RemoteViews landscape, RemoteViews portrait) {
        if (landscape == null || portrait == null) {
            throw new RuntimeException("Both RemoteViews must be non-null");
        }
        if (landscape.mApplication.uid != portrait.mApplication.uid
                || !landscape.mApplication.packageName.equals(portrait.mApplication.packageName)) {
            throw new RuntimeException("Both RemoteViews must share the same package and user");
        }
        mApplication = portrait.mApplication;
        mLayoutId = portrait.getLayoutId();

        mLandscape = landscape;
        mPortrait = portrait;

        // setup the memory usage statistics
        mMemoryUsageCounter = new MemoryUsageCounter();

        mBitmapCache = new BitmapCache();
        configureRemoteViewsAsChild(landscape);
        configureRemoteViewsAsChild(portrait);

        recalculateMemoryUsage();
    }

    /**
     * Reads a RemoteViews object from a parcel.
     *
     * @param parcel
     */
    public RemoteViews(Parcel parcel) {
        this(parcel, null, null, 0);
    }

    private RemoteViews(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info, int depth) {
        if (depth > MAX_NESTED_VIEWS
                && (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)) {
            throw new IllegalArgumentException("Too many nested views.");
        }
        depth++;

        int mode = parcel.readInt();

        // We only store a bitmap cache in the root of the RemoteViews.
        if (bitmapCache == null) {
            mBitmapCache = new BitmapCache(parcel);
        } else {
            setBitmapCache(bitmapCache);
            setNotRoot();
        }

        if (mode == MODE_NORMAL) {
            mApplication = parcel.readInt() == 0 ? info :
                    ApplicationInfo.CREATOR.createFromParcel(parcel);
            mLayoutId = parcel.readInt();
            mIsWidgetCollectionChild = parcel.readInt() == 1;

            int count = parcel.readInt();
            if (count > 0) {
                mActions = new ArrayList<Action>(count);
                for (int i=0; i<count; i++) {
                    int tag = parcel.readInt();
                    switch (tag) {
                        case SET_ON_CLICK_PENDING_INTENT_TAG:
                            mActions.add(new SetOnClickPendingIntent(parcel));
                            break;
                        case SET_DRAWABLE_PARAMETERS_TAG:
                            mActions.add(new SetDrawableParameters(parcel));
                            break;
                        case REFLECTION_ACTION_TAG:
                            mActions.add(new ReflectionAction(parcel));
                            break;
                        case VIEW_GROUP_ACTION_ADD_TAG:
                            mActions.add(new ViewGroupActionAdd(parcel, mBitmapCache, mApplication,
                                    depth));
                            break;
                        case VIEW_GROUP_ACTION_REMOVE_TAG:
                            mActions.add(new ViewGroupActionRemove(parcel));
                            break;
                        case SET_REFLECTION_ACTION_WITHOUT_PARAMS_TAG:
                            mActions.add(new ReflectionActionWithoutParams(parcel));
                            break;
                        case SET_EMPTY_VIEW_ACTION_TAG:
                            mActions.add(new SetEmptyView(parcel));
                            break;
                        case SET_PENDING_INTENT_TEMPLATE_TAG:
                            mActions.add(new SetPendingIntentTemplate(parcel));
                            break;
                        case SET_ON_CLICK_FILL_IN_INTENT_TAG:
                            mActions.add(new SetOnClickFillInIntent(parcel));
                            break;
                        case SET_REMOTE_VIEW_ADAPTER_INTENT_TAG:
                            mActions.add(new SetRemoteViewsAdapterIntent(parcel));
                            break;
                        case TEXT_VIEW_DRAWABLE_ACTION_TAG:
                            mActions.add(new TextViewDrawableAction(parcel));
                            break;
                        case TEXT_VIEW_SIZE_ACTION_TAG:
                            mActions.add(new TextViewSizeAction(parcel));
                            break;
                        case VIEW_PADDING_ACTION_TAG:
                            mActions.add(new ViewPaddingAction(parcel));
                            break;
                        case BITMAP_REFLECTION_ACTION_TAG:
                            mActions.add(new BitmapReflectionAction(parcel));
                            break;
                        case SET_REMOTE_VIEW_ADAPTER_LIST_TAG:
                            mActions.add(new SetRemoteViewsAdapterList(parcel));
                            break;
                        case TEXT_VIEW_DRAWABLE_COLOR_FILTER_ACTION_TAG:
                            mActions.add(new TextViewDrawableColorFilterAction(parcel));
                            break;
                        case SET_REMOTE_INPUTS_ACTION_TAG:
                            mActions.add(new SetRemoteInputsAction(parcel));
                            break;
                        case LAYOUT_PARAM_ACTION_TAG:
                            mActions.add(new LayoutParamAction(parcel));
                            break;
                        default:
                            throw new ActionException("Tag " + tag + " not found");
                    }
                }
            }
        } else {
            // MODE_HAS_LANDSCAPE_AND_PORTRAIT
            mLandscape = new RemoteViews(parcel, mBitmapCache, info, depth);
            mPortrait = new RemoteViews(parcel, mBitmapCache, mLandscape.mApplication, depth);
            mApplication = mPortrait.mApplication;
            mLayoutId = mPortrait.getLayoutId();
        }

        // setup the memory usage statistics
        mMemoryUsageCounter = new MemoryUsageCounter();
        recalculateMemoryUsage();
    }

    /**
     * Returns a deep copy of the RemoteViews object. The RemoteView may not be
     * attached to another RemoteView -- it must be the root of a hierarchy.
     *
     * @throws IllegalStateException if this is not the root of a RemoteView
     *         hierarchy
     */
    @Override
    public RemoteViews clone() {
        synchronized (this) {
            Preconditions.checkState(mIsRoot, "RemoteView has been attached to another RemoteView. "
                    + "May only clone the root of a RemoteView hierarchy.");

            Parcel p = Parcel.obtain();

            // Do not parcel the Bitmap cache - doing so creates an expensive copy of all bitmaps.
            // Instead pretend we're not owning the cache while parceling.
            mIsRoot = false;
            writeToParcel(p, PARCELABLE_ELIDE_DUPLICATES);
            p.setDataPosition(0);
            mIsRoot = true;

            RemoteViews rv = new RemoteViews(p, mBitmapCache.clone(), mApplication, 0);
            rv.mIsRoot = true;

            p.recycle();
            return rv;
        }
    }

    public String getPackage() {
        return (mApplication != null) ? mApplication.packageName : null;
    }

    /**
     * Returns the layout id of the root layout associated with this RemoteViews. In the case
     * that the RemoteViews has both a landscape and portrait root, this will return the layout
     * id associated with the portrait layout.
     *
     * @return the layout id.
     */
    public int getLayoutId() {
        return mLayoutId;
    }

    /*
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     */
    void setIsWidgetCollectionChild(boolean isWidgetCollectionChild) {
        mIsWidgetCollectionChild = isWidgetCollectionChild;
    }

    /**
     * Updates the memory usage statistics.
     */
    private void recalculateMemoryUsage() {
        mMemoryUsageCounter.clear();

        if (!hasLandscapeAndPortraitLayouts()) {
            // Accumulate the memory usage for each action
            if (mActions != null) {
                final int count = mActions.size();
                for (int i= 0; i < count; ++i) {
                    mActions.get(i).updateMemoryUsageEstimate(mMemoryUsageCounter);
                }
            }
            if (mIsRoot) {
                mBitmapCache.addBitmapMemory(mMemoryUsageCounter);
            }
        } else {
            mMemoryUsageCounter.increment(mLandscape.estimateMemoryUsage());
            mMemoryUsageCounter.increment(mPortrait.estimateMemoryUsage());
            mBitmapCache.addBitmapMemory(mMemoryUsageCounter);
        }
    }

    /**
     * Recursively sets BitmapCache in the hierarchy and update the bitmap ids.
     */
    private void setBitmapCache(BitmapCache bitmapCache) {
        mBitmapCache = bitmapCache;
        if (!hasLandscapeAndPortraitLayouts()) {
            if (mActions != null) {
                final int count = mActions.size();
                for (int i= 0; i < count; ++i) {
                    mActions.get(i).setBitmapCache(bitmapCache);
                }
            }
        } else {
            mLandscape.setBitmapCache(bitmapCache);
            mPortrait.setBitmapCache(bitmapCache);
        }
    }

    /**
     * Returns an estimate of the bitmap heap memory usage for this RemoteViews.
     */
    /** @hide */
    public int estimateMemoryUsage() {
        return mMemoryUsageCounter.getMemoryUsage();
    }

    /**
     * Add an action to be executed on the remote side when apply is called.
     *
     * @param a The action to add
     */
    private void addAction(Action a) {
        if (hasLandscapeAndPortraitLayouts()) {
            throw new RuntimeException("RemoteViews specifying separate landscape and portrait" +
                    " layouts cannot be modified. Instead, fully configure the landscape and" +
                    " portrait layouts individually before constructing the combined layout.");
        }
        if (mActions == null) {
            mActions = new ArrayList<Action>();
        }
        mActions.add(a);

        // update the memory usage stats
        a.updateMemoryUsageEstimate(mMemoryUsageCounter);
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View)} after inflating the
     * given {@link RemoteViews}. This allows users to build "nested"
     * {@link RemoteViews}. In cases where consumers of {@link RemoteViews} may
     * recycle layouts, use {@link #removeAllViews(int)} to clear any existing
     * children.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add child into.
     * @param nestedView {@link RemoteViews} that describes the child.
     */
    public void addView(int viewId, RemoteViews nestedView) {
        addAction(nestedView == null
                ? new ViewGroupActionRemove(viewId)
                : new ViewGroupActionAdd(viewId, nestedView));
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View, int)} after inflating the
     * given {@link RemoteViews}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add the child into.
     * @param nestedView {@link RemoveViews} of the child to add.
     * @param index The position at which to add the child.
     *
     * @hide
     */
    public void addView(int viewId, RemoteViews nestedView, int index) {
        addAction(new ViewGroupActionAdd(viewId, nestedView, index));
    }

    /**
     * Equivalent to calling {@link ViewGroup#removeAllViews()}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove all
     *            children from.
     */
    public void removeAllViews(int viewId) {
        addAction(new ViewGroupActionRemove(viewId));
    }

    /**
     * Removes all views in the {@link ViewGroup} specified by the {@code viewId} except for any
     * child that has the {@code viewIdToKeep} as its id.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove children from.
     * @param viewIdToKeep The id of a child that should not be removed.
     *
     * @hide
     */
    public void removeAllViewsExceptId(int viewId, int viewIdToKeep) {
        addAction(new ViewGroupActionRemove(viewId, viewIdToKeep));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showNext()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showNext()}
     */
    public void showNext(int viewId) {
        addAction(new ReflectionActionWithoutParams(viewId, "showNext"));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showPrevious()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showPrevious()}
     */
    public void showPrevious(int viewId) {
        addAction(new ReflectionActionWithoutParams(viewId, "showPrevious"));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#setDisplayedChild(int)}
     *
     * @param viewId The id of the view on which to call
     *               {@link AdapterViewAnimator#setDisplayedChild(int)}
     */
    public void setDisplayedChild(int viewId, int childIndex) {
        setInt(viewId, "setDisplayedChild", childIndex);
    }

    /**
     * Equivalent to calling {@link View#setVisibility(int)}
     *
     * @param viewId The id of the view whose visibility should change
     * @param visibility The new visibility for the view
     */
    public void setViewVisibility(int viewId, int visibility) {
        setInt(viewId, "setVisibility", visibility);
    }

    /**
     * Equivalent to calling {@link TextView#setText(CharSequence)}
     *
     * @param viewId The id of the view whose text should change
     * @param text The new text for the view
     */
    public void setTextViewText(int viewId, CharSequence text) {
        setCharSequence(viewId, "setText", text);
    }

    /**
     * Equivalent to calling {@link TextView#setTextSize(int, float)}
     *
     * @param viewId The id of the view whose text size should change
     * @param units The units of size (e.g. COMPLEX_UNIT_SP)
     * @param size The size of the text
     */
    public void setTextViewTextSize(int viewId, int units, float size) {
        addAction(new TextViewSizeAction(viewId, units, size));
    }

    /**
     * Equivalent to calling
     * {@link TextView#setCompoundDrawablesWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left The id of a drawable to place to the left of the text, or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param right The id of a drawable to place to the right of the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawables(int viewId, int left, int top, int right, int bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start The id of a drawable to place before the text (relative to the
     * layout direction), or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param end The id of a drawable to place after the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawablesRelative(int viewId, int start, int top, int end, int bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to applying a color filter on one of the drawables in
     * {@link android.widget.TextView#getCompoundDrawablesRelative()}.
     *
     * @param viewId The id of the view whose text should change.
     * @param index  The index of the drawable in the array of
     *               {@link android.widget.TextView#getCompoundDrawablesRelative()} to set the color
     *               filter on. Must be in [0, 3].
     * @param color  The color of the color filter. See
     *               {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)}.
     * @param mode   The mode of the color filter. See
     *               {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)}.
     * @hide
     */
    public void setTextViewCompoundDrawablesRelativeColorFilter(int viewId,
            int index, int color, PorterDuff.Mode mode) {
        if (index < 0 || index >= 4) {
            throw new IllegalArgumentException("index must be in range [0, 3].");
        }
        addAction(new TextViewDrawableColorFilterAction(viewId, true, index, color, mode));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left an Icon to place to the left of the text, or 0
     * @param top an Icon to place above the text, or 0
     * @param right an Icon to place to the right of the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawables(int viewId, Icon left, Icon top, Icon right, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start an Icon to place before the text (relative to the
     * layout direction), or 0
     * @param top an Icon to place above the text, or 0
     * @param end an Icon to place after the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawablesRelative(int viewId, Icon start, Icon top, Icon end, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to calling {@link ImageView#setImageResource(int)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param srcId The new resource id for the drawable
     */
    public void setImageViewResource(int viewId, int srcId) {
        setInt(viewId, "setImageResource", srcId);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageURI(Uri)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param uri The Uri for the image
     */
    public void setImageViewUri(int viewId, Uri uri) {
        setUri(viewId, "setImageURI", uri);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageBitmap(Bitmap)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(int viewId, Bitmap bitmap) {
        setBitmap(viewId, "setImageBitmap", bitmap);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageIcon(Icon)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param icon The new Icon for the ImageView
     */
    public void setImageViewIcon(int viewId, Icon icon) {
        setIcon(viewId, "setImageIcon", icon);
    }

    /**
     * Equivalent to calling {@link AdapterView#setEmptyView(View)}
     *
     * @param viewId The id of the view on which to set the empty view
     * @param emptyViewId The view id of the empty view
     */
    public void setEmptyView(int viewId, int emptyViewId) {
        addAction(new SetEmptyView(viewId, emptyViewId));
    }

    /**
     * Equivalent to calling {@link Chronometer#setBase Chronometer.setBase},
     * {@link Chronometer#setFormat Chronometer.setFormat},
     * and {@link Chronometer#start Chronometer.start()} or
     * {@link Chronometer#stop Chronometer.stop()}.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param base The time at which the timer would have read 0:00.  This
     *             time should be based off of
     *             {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * @param format The Chronometer format string, or null to
     *               simply display the timer value.
     * @param started True if you want the clock to be started, false if not.
     *
     * @see #setChronometerCountDown(int, boolean)
     */
    public void setChronometer(int viewId, long base, String format, boolean started) {
        setLong(viewId, "setBase", base);
        setString(viewId, "setFormat", format);
        setBoolean(viewId, "setStarted", started);
    }

    /**
     * Equivalent to calling {@link Chronometer#setCountDown(boolean) Chronometer.setCountDown} on
     * the chronometer with the given viewId.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param isCountDown True if you want the chronometer to count down to base instead of
     *                    counting up.
     */
    public void setChronometerCountDown(int viewId, boolean isCountDown) {
        setBoolean(viewId, "setCountDown", isCountDown);
    }

    /**
     * Equivalent to calling {@link ProgressBar#setMax ProgressBar.setMax},
     * {@link ProgressBar#setProgress ProgressBar.setProgress}, and
     * {@link ProgressBar#setIndeterminate ProgressBar.setIndeterminate}
     *
     * If indeterminate is true, then the values for max and progress are ignored.
     *
     * @param viewId The id of the {@link ProgressBar} to change
     * @param max The 100% value for the progress bar
     * @param progress The current value of the progress bar.
     * @param indeterminate True if the progress bar is indeterminate,
     *                false if not.
     */
    public void setProgressBar(int viewId, int max, int progress,
            boolean indeterminate) {
        setBoolean(viewId, "setIndeterminate", indeterminate);
        if (!indeterminate) {
            setInt(viewId, "setMax", max);
            setInt(viewId, "setProgress", progress);
        }
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     *
     * When setting the on-click action of items within collections (eg. {@link ListView},
     * {@link StackView} etc.), this method will not work. Instead, use {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)} in conjunction with
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when clicked
     * @param pendingIntent The {@link PendingIntent} to send when user clicks
     */
    public void setOnClickPendingIntent(int viewId, PendingIntent pendingIntent) {
        addAction(new SetOnClickPendingIntent(viewId, pendingIntent));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * this method should be used to set a single PendingIntent template on the collection, and
     * individual items can differentiate their on-click behavior using
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the collection who's children will use this PendingIntent template
     *          when clicked
     * @param pendingIntentTemplate The {@link PendingIntent} to be combined with extras specified
     *          by a child of viewId and executed when that child is clicked
     */
    public void setPendingIntentTemplate(int viewId, PendingIntent pendingIntentTemplate) {
        addAction(new SetPendingIntentTemplate(viewId, pendingIntentTemplate));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * a single PendingIntent template can be set on the collection, see {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
     * action of a given item can be distinguished by setting a fillInIntent on that item. The
     * fillInIntent is then combined with the PendingIntent template in order to determine the final
     * intent which will be executed when the item is clicked. This works as follows: any fields
     * which are left blank in the PendingIntent template, but are provided by the fillInIntent
     * will be overwritten, and the resulting PendingIntent will be used. The rest
     * of the PendingIntent template will then be filled in with the associated fields that are
     * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
     *
     * @param viewId The id of the view on which to set the fillInIntent
     * @param fillInIntent The intent which will be combined with the parent's PendingIntent
     *        in order to determine the on-click behavior of the view specified by viewId
     */
    public void setOnClickFillInIntent(int viewId, Intent fillInIntent) {
        addAction(new SetOnClickFillInIntent(viewId, fillInIntent));
    }

    /**
     * @hide
     * Equivalent to calling a combination of {@link Drawable#setAlpha(int)},
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * and/or {@link Drawable#setLevel(int)} on the {@link Drawable} of a given
     * view.
     * <p>
     * You can omit specific calls by marking their values with null or -1.
     *
     * @param viewId The id of the view that contains the target
     *            {@link Drawable}
     * @param targetBackground If true, apply these parameters to the
     *            {@link Drawable} returned by
     *            {@link android.view.View#getBackground()}. Otherwise, assume
     *            the target view is an {@link ImageView} and apply them to
     *            {@link ImageView#getDrawable()}.
     * @param alpha Specify an alpha value for the drawable, or -1 to leave
     *            unchanged.
     * @param colorFilter Specify a color for a
     *            {@link android.graphics.ColorFilter} for this drawable. This will be ignored if
     *            {@code mode} is {@code null}.
     * @param mode Specify a PorterDuff mode for this drawable, or null to leave
     *            unchanged.
     * @param level Specify the level for the drawable, or -1 to leave
     *            unchanged.
     */
    public void setDrawableParameters(int viewId, boolean targetBackground, int alpha,
            int colorFilter, PorterDuff.Mode mode, int level) {
        addAction(new SetDrawableParameters(viewId, targetBackground, alpha,
                colorFilter, mode, level));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressBackgroundTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressBackgroundTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressBackgroundTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setIndeterminateTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressIndeterminateTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setIndeterminateTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * Equivalent to calling {@link android.widget.TextView#setTextColor(int)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param color Sets the text color for all the states (normal, selected,
     *            focused) to be this color.
     */
    public void setTextColor(int viewId, @ColorInt int color) {
        setInt(viewId, "setTextColor", color);
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.TextView#setTextColor(ColorStateList)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param colors the text colors to set
     */
    public void setTextColor(int viewId, @ColorInt ColorStateList colors) {
        addAction(new ReflectionAction(viewId, "setTextColor", ReflectionAction.COLOR_STATE_LIST,
                colors));
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     *
     * @param appWidgetId The id of the app widget which contains the specified view. (This
     *      parameter is ignored in this deprecated method)
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     * @deprecated This method has been deprecated. See
     *      {@link android.widget.RemoteViews#setRemoteAdapter(int, Intent)}
     */
    @Deprecated
    public void setRemoteAdapter(int appWidgetId, int viewId, Intent intent) {
        setRemoteAdapter(viewId, intent);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     * Can only be used for App Widgets.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     */
    public void setRemoteAdapter(int viewId, Intent intent) {
        addAction(new SetRemoteViewsAdapterIntent(viewId, intent));
    }

    /**
     * Creates a simple Adapter for the viewId specified. The viewId must point to an AdapterView,
     * ie. {@link ListView}, {@link GridView}, {@link StackView} or {@link AdapterViewAnimator}.
     * This is a simpler but less flexible approach to populating collection widgets. Its use is
     * encouraged for most scenarios, as long as the total memory within the list of RemoteViews
     * is relatively small (ie. doesn't contain large or numerous Bitmaps, see {@link
     * RemoteViews#setImageViewBitmap}). In the case of numerous images, the use of API is still
     * possible by setting image URIs instead of Bitmaps, see {@link RemoteViews#setImageViewUri}.
     *
     * This API is supported in the compatibility library for previous API levels, see
     * RemoteViewsCompat.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param list The list of RemoteViews which will populate the view specified by viewId.
     * @param viewTypeCount The maximum number of unique layout id's used to construct the list of
     *      RemoteViews. This count cannot change during the life-cycle of a given widget, so this
     *      parameter should account for the maximum possible number of types that may appear in the
     *      See {@link Adapter#getViewTypeCount()}.
     *
     * @hide
     */
    public void setRemoteAdapter(int viewId, ArrayList<RemoteViews> list, int viewTypeCount) {
        addAction(new SetRemoteViewsAdapterList(viewId, list, viewTypeCount));
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollToPosition(int)}.
     *
     * @param viewId The id of the view to change
     * @param position Scroll to this adapter position
     */
    public void setScrollPosition(int viewId, int position) {
        setInt(viewId, "smoothScrollToPosition", position);
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollByOffset(int)}.
     *
     * @param viewId The id of the view to change
     * @param offset Scroll by this adapter position offset
     */
    public void setRelativeScrollPosition(int viewId, int offset) {
        setInt(viewId, "smoothScrollByOffset", offset);
    }

    /**
     * Equivalent to calling {@link android.view.View#setPadding(int, int, int, int)}.
     *
     * @param viewId The id of the view to change
     * @param left the left padding in pixels
     * @param top the top padding in pixels
     * @param right the right padding in pixels
     * @param bottom the bottom padding in pixels
     */
    public void setViewPadding(int viewId, int left, int top, int right, int bottom) {
        addAction(new ViewPaddingAction(viewId, left, top, right, bottom));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.view.ViewGroup.MarginLayoutParams#setMarginEnd(int)}.
     * Only works if the {@link View#getLayoutParams()} supports margins.
     * Hidden for now since we don't want to support this for all different layout margins yet.
     *
     * @param viewId The id of the view to change
     * @param endMarginDimen a dimen resource to read the margin from or 0 to clear the margin.
     */
    public void setViewLayoutMarginEndDimen(int viewId, @DimenRes int endMarginDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_MARGIN_END_DIMEN,
                endMarginDimen));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.MarginLayoutParams#bottomMargin}.
     *
     * @param bottomMarginDimen a dimen resource to read the margin from or 0 to clear the margin.
     * @hide
     */
    public void setViewLayoutMarginBottomDimen(int viewId, @DimenRes int bottomMarginDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_MARGIN_BOTTOM_DIMEN,
                bottomMarginDimen));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#width}.
     *
     * @param layoutWidth one of 0, MATCH_PARENT or WRAP_CONTENT. Other sizes are not allowed
     *                    because they behave poorly when the density changes.
     * @hide
     */
    public void setViewLayoutWidth(int viewId, int layoutWidth) {
        if (layoutWidth != 0 && layoutWidth != ViewGroup.LayoutParams.MATCH_PARENT
                && layoutWidth != ViewGroup.LayoutParams.WRAP_CONTENT) {
            throw new IllegalArgumentException("Only supports 0, WRAP_CONTENT and MATCH_PARENT");
        }
        mActions.add(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_WIDTH, layoutWidth));
    }

    /**
     * Call a method taking one boolean on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBoolean(int viewId, String methodName, boolean value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BOOLEAN, value));
    }

    /**
     * Call a method taking one byte on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setByte(int viewId, String methodName, byte value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BYTE, value));
    }

    /**
     * Call a method taking one short on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setShort(int viewId, String methodName, short value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.SHORT, value));
    }

    /**
     * Call a method taking one int on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setInt(int viewId, String methodName, int value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INT, value));
    }

    /**
     * Call a method taking one long on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setLong(int viewId, String methodName, long value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.LONG, value));
    }

    /**
     * Call a method taking one float on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setFloat(int viewId, String methodName, float value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.FLOAT, value));
    }

    /**
     * Call a method taking one double on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setDouble(int viewId, String methodName, double value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.DOUBLE, value));
    }

    /**
     * Call a method taking one char on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setChar(int viewId, String methodName, char value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR, value));
    }

    /**
     * Call a method taking one String on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setString(int viewId, String methodName, String value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.STRING, value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setCharSequence(int viewId, String methodName, CharSequence value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR_SEQUENCE, value));
    }

    /**
     * Call a method taking one Uri on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setUri(int viewId, String methodName, Uri value) {
        if (value != null) {
            // Resolve any filesystem path before sending remotely
            value = value.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                value.checkFileUriExposed("RemoteViews.setUri()");
            }
        }
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.URI, value));
    }

    /**
     * Call a method taking one Bitmap on a view in the layout for this RemoteViews.
     * @more
     * <p class="note">The bitmap will be flattened into the parcel if this object is
     * sent across processes, so it may end up using a lot of memory, and may be fairly slow.</p>
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBitmap(int viewId, String methodName, Bitmap value) {
        addAction(new BitmapReflectionAction(viewId, methodName, value));
    }

    /**
     * Call a method taking one Bundle on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBundle(int viewId, String methodName, Bundle value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BUNDLE, value));
    }

    /**
     * Call a method taking one Intent on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.content.Intent} to pass the method.
     */
    public void setIntent(int viewId, String methodName, Intent value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INTENT, value));
    }

    /**
     * Call a method taking one Icon on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.graphics.drawable.Icon} to pass the method.
     */
    public void setIcon(int viewId, String methodName, Icon value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.ICON, value));
    }

    /**
     * Equivalent to calling View.setContentDescription(CharSequence).
     *
     * @param viewId The id of the view whose content description should change.
     * @param contentDescription The new content description for the view.
     */
    public void setContentDescription(int viewId, CharSequence contentDescription) {
        setCharSequence(viewId, "setContentDescription", contentDescription);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalBefore(int)}.
     *
     * @param viewId The id of the view whose before view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalBefore(int viewId, int nextId) {
        setInt(viewId, "setAccessibilityTraversalBefore", nextId);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalAfter(int)}.
     *
     * @param viewId The id of the view whose after view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalAfter(int viewId, int nextId) {
        setInt(viewId, "setAccessibilityTraversalAfter", nextId);
    }

    /**
     * Equivalent to calling {@link View#setLabelFor(int)}.
     *
     * @param viewId The id of the view whose property to set.
     * @param labeledId The id of a view for which this view serves as a label.
     */
    public void setLabelFor(int viewId, int labeledId) {
        setInt(viewId, "setLabelFor", labeledId);
    }

    private RemoteViews getRemoteViewsToApply(Context context) {
        if (hasLandscapeAndPortraitLayouts()) {
            int orientation = context.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return mLandscape;
            } else {
                return mPortrait;
            }
        }
        return this;
    }

    /**
     * Inflates the view hierarchy represented by this object and applies
     * all of the actions.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @return The inflated view hierarchy
     */
    public View apply(Context context, ViewGroup parent) {
        return apply(context, parent, null);
    }

    /** @hide */
    public View apply(Context context, ViewGroup parent, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        View result = inflateView(context, rvToApply, parent);
        loadTransitionOverride(context, handler);

        rvToApply.performApply(result, parent, handler);

        return result;
    }

    private View inflateView(Context context, RemoteViews rv, ViewGroup parent) {
        // RemoteViews may be built by an application installed in another
        // user. So build a context that loads resources from that user but
        // still returns the current users userId so settings like data / time formats
        // are loaded without requiring cross user persmissions.
        final Context contextForResources = getContextForResources(context);
        Context inflationContext = new RemoteViewsContextWrapper(context, contextForResources);

        LayoutInflater inflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Clone inflater so we load resources from correct context and
        // we don't add a filter to the static version returned by getSystemService.
        inflater = inflater.cloneInContext(inflationContext);
        inflater.setFilter(this);
        View v = inflater.inflate(rv.getLayoutId(), parent, false);
        v.setTagInternal(R.id.widget_frame, rv.getLayoutId());
        return v;
    }

    private static void loadTransitionOverride(Context context,
            RemoteViews.OnClickHandler handler) {
        if (handler != null && context.getResources().getBoolean(
                com.android.internal.R.bool.config_overrideRemoteViewsActivityTransition)) {
            TypedArray windowStyle = context.getTheme().obtainStyledAttributes(
                    com.android.internal.R.styleable.Window);
            int windowAnimations = windowStyle.getResourceId(
                    com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
            TypedArray windowAnimationStyle = context.obtainStyledAttributes(
                    windowAnimations, com.android.internal.R.styleable.WindowAnimation);
            handler.setEnterAnimationId(windowAnimationStyle.getResourceId(
                    com.android.internal.R.styleable.
                            WindowAnimation_activityOpenRemoteViewsEnterAnimation, 0));
            windowStyle.recycle();
            windowAnimationStyle.recycle();
        }
    }

    /**
     * Implement this interface to receive a callback when
     * {@link #applyAsync} or {@link #reapplyAsync} is finished.
     * @hide
     */
    public interface OnViewAppliedListener {
        void onViewApplied(View v);

        void onError(Exception e);
    }

    /**
     * Applies the views asynchronously, moving as much of the task on the background
     * thread as possible.
     *
     * @see #apply(Context, ViewGroup)
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used.
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal applyAsync(
            Context context, ViewGroup parent, Executor executor, OnViewAppliedListener listener) {
        return applyAsync(context, parent, executor, listener, null);
    }

    private CancellationSignal startTaskOnExecutor(AsyncApplyTask task, Executor executor) {
        CancellationSignal cancelSignal = new CancellationSignal();
        cancelSignal.setOnCancelListener(task);

        task.executeOnExecutor(executor == null ? AsyncTask.THREAD_POOL_EXECUTOR : executor);
        return cancelSignal;
    }

    /** @hide */
    public CancellationSignal applyAsync(Context context, ViewGroup parent,
            Executor executor, OnViewAppliedListener listener, OnClickHandler handler) {
        return startTaskOnExecutor(getAsyncApplyTask(context, parent, listener, handler), executor);
    }

    private AsyncApplyTask getAsyncApplyTask(Context context, ViewGroup parent,
            OnViewAppliedListener listener, OnClickHandler handler) {
        return new AsyncApplyTask(getRemoteViewsToApply(context), parent, context, listener,
                handler, null);
    }

    private class AsyncApplyTask extends AsyncTask<Void, Void, ViewTree>
            implements CancellationSignal.OnCancelListener {
        final RemoteViews mRV;
        final ViewGroup mParent;
        final Context mContext;
        final OnViewAppliedListener mListener;
        final OnClickHandler mHandler;

        private View mResult;
        private ViewTree mTree;
        private Action[] mActions;
        private Exception mError;

        private AsyncApplyTask(
                RemoteViews rv, ViewGroup parent, Context context, OnViewAppliedListener listener,
                OnClickHandler handler, View result) {
            mRV = rv;
            mParent = parent;
            mContext = context;
            mListener = listener;
            mHandler = handler;

            mResult = result;
            loadTransitionOverride(context, handler);
        }

        @Override
        protected ViewTree doInBackground(Void... params) {
            try {
                if (mResult == null) {
                    mResult = inflateView(mContext, mRV, mParent);
                }

                mTree = new ViewTree(mResult);
                if (mRV.mActions != null) {
                    int count = mRV.mActions.size();
                    mActions = new Action[count];
                    for (int i = 0; i < count && !isCancelled(); i++) {
                        // TODO: check if isCancelled in nested views.
                        mActions[i] = mRV.mActions.get(i).initActionAsync(mTree, mParent, mHandler);
                    }
                } else {
                    mActions = null;
                }
                return mTree;
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(ViewTree viewTree) {
            if (mError == null) {
                try {
                    if (mActions != null) {
                        OnClickHandler handler = mHandler == null
                                ? DEFAULT_ON_CLICK_HANDLER : mHandler;
                        for (Action a : mActions) {
                            a.apply(viewTree.mRoot, mParent, handler);
                        }
                    }
                } catch (Exception e) {
                    mError = e;
                }
            }

            if (mListener != null) {
                if (mError != null) {
                    mListener.onError(mError);
                } else {
                    mListener.onViewApplied(viewTree.mRoot);
                }
            } else if (mError != null) {
                if (mError instanceof ActionException) {
                    throw (ActionException) mError;
                } else {
                    throw new ActionException(mError);
                }
            }
        }

        @Override
        public void onCancel() {
            cancel(true);
        }
    }

    /**
     * Applies all of the actions to the provided view.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     */
    public void reapply(Context context, View v) {
        reapply(context, v, null);
    }

    /** @hide */
    public void reapply(Context context, View v, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        // In the case that a view has this RemoteViews applied in one orientation, is persisted
        // across orientation change, and has the RemoteViews re-applied in the new orientation,
        // we throw an exception, since the layouts may be completely unrelated.
        if (hasLandscapeAndPortraitLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        rvToApply.performApply(v, (ViewGroup) v.getParent(), handler);
    }

    /**
     * Applies all the actions to the provided view, moving as much of the task on the background
     * thread as possible.
     *
     * @see #reapply(Context, View)
     * @param context Default context to use
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal reapplyAsync(
            Context context, View v, Executor executor, OnViewAppliedListener listener) {
        return reapplyAsync(context, v, executor, listener, null);
    }

    /** @hide */
    public CancellationSignal reapplyAsync(Context context, View v, Executor executor,
            OnViewAppliedListener listener, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        // In the case that a view has this RemoteViews applied in one orientation, is persisted
        // across orientation change, and has the RemoteViews re-applied in the new orientation,
        // we throw an exception, since the layouts may be completely unrelated.
        if (hasLandscapeAndPortraitLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        return startTaskOnExecutor(new AsyncApplyTask(rvToApply, (ViewGroup) v.getParent(),
                context, listener, handler, v), executor);
    }

    private void performApply(View v, ViewGroup parent, OnClickHandler handler) {
        if (mActions != null) {
            handler = handler == null ? DEFAULT_ON_CLICK_HANDLER : handler;
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                Action a = mActions.get(i);
                a.apply(v, parent, handler);
            }
        }
    }

    /**
     * Returns true if the RemoteViews contains potentially costly operations and should be
     * applied asynchronously.
     *
     * @hide
     */
    public boolean prefersAsyncApply() {
        if (mActions != null) {
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                if (mActions.get(i).prefersAsyncApply()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Context getContextForResources(Context context) {
        if (mApplication != null) {
            if (context.getUserId() == UserHandle.getUserId(mApplication.uid)
                    && context.getPackageName().equals(mApplication.packageName)) {
                return context;
            }
            try {
                return context.createApplicationContext(mApplication,
                        Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Package name " + mApplication.packageName + " not found");
            }
        }

        return context;
    }

    /**
     * Returns the number of actions in this RemoteViews. Can be used as a sequence number.
     *
     * @hide
     */
    public int getSequenceNumber() {
        return (mActions == null) ? 0 : mActions.size();
    }

    /* (non-Javadoc)
     * Used to restrict the views which can be inflated
     *
     * @see android.view.LayoutInflater.Filter#onLoadClass(java.lang.Class)
     */
    public boolean onLoadClass(Class clazz) {
        return clazz.isAnnotationPresent(RemoteView.class);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (!hasLandscapeAndPortraitLayouts()) {
            dest.writeInt(MODE_NORMAL);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            if (!mIsRoot && (flags & PARCELABLE_ELIDE_DUPLICATES) != 0) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mApplication.writeToParcel(dest, flags);
            }
            dest.writeInt(mLayoutId);
            dest.writeInt(mIsWidgetCollectionChild ? 1 : 0);
            int count;
            if (mActions != null) {
                count = mActions.size();
            } else {
                count = 0;
            }
            dest.writeInt(count);
            for (int i=0; i<count; i++) {
                Action a = mActions.get(i);
                a.writeToParcel(dest, a.hasSameAppInfo(mApplication)
                        ? PARCELABLE_ELIDE_DUPLICATES : 0);
            }
        } else {
            dest.writeInt(MODE_HAS_LANDSCAPE_AND_PORTRAIT);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            mLandscape.writeToParcel(dest, flags);
            // Both RemoteViews already share the same package and user
            mPortrait.writeToParcel(dest, flags | PARCELABLE_ELIDE_DUPLICATES);
        }
    }

    private static ApplicationInfo getApplicationInfo(String packageName, int userId) {
        if (packageName == null) {
            return null;
        }

        // Get the application for the passed in package and user.
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            throw new IllegalStateException("Cannot create remote views out of an aplication.");
        }

        ApplicationInfo applicationInfo = application.getApplicationInfo();
        if (UserHandle.getUserId(applicationInfo.uid) != userId
                || !applicationInfo.packageName.equals(packageName)) {
            try {
                Context context = application.getBaseContext().createPackageContextAsUser(
                        packageName, 0, new UserHandle(userId));
                applicationInfo = context.getApplicationInfo();
            } catch (NameNotFoundException nnfe) {
                throw new IllegalArgumentException("No such package " + packageName);
            }
        }

        return applicationInfo;
    }

    /**
     * Parcelable.Creator that instantiates RemoteViews objects
     */
    public static final Parcelable.Creator<RemoteViews> CREATOR = new Parcelable.Creator<RemoteViews>() {
        public RemoteViews createFromParcel(Parcel parcel) {
            return new RemoteViews(parcel);
        }

        public RemoteViews[] newArray(int size) {
            return new RemoteViews[size];
        }
    };

    /**
     * A representation of the view hierarchy. Only views which have a valid ID are added
     * and can be searched.
     */
    private static class ViewTree {
        private static final int INSERT_AT_END_INDEX = -1;
        private View mRoot;
        private ArrayList<ViewTree> mChildren;

        private ViewTree(View root) {
            mRoot = root;
        }

        public void createTree() {
            if (mChildren != null) {
                return;
            }

            mChildren = new ArrayList<>();
            if (mRoot instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) mRoot;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    addViewChild(vg.getChildAt(i));
                }
            }
        }

        public ViewTree findViewTreeById(int id) {
            if (mRoot.getId() == id) {
                return this;
            }
            if (mChildren == null) {
                return null;
            }
            for (ViewTree tree : mChildren) {
                ViewTree result = tree.findViewTreeById(id);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        public void replaceView(View v) {
            mRoot = v;
            mChildren = null;
            createTree();
        }

        public <T extends View> T findViewById(int id) {
            if (mChildren == null) {
                return mRoot.findViewById(id);
            }
            ViewTree tree = findViewTreeById(id);
            return tree == null ? null : (T) tree.mRoot;
        }

        public void addChild(ViewTree child) {
            addChild(child, INSERT_AT_END_INDEX);
        }

        /**
         * Adds the given {@link ViewTree} as a child at the given index.
         *
         * @param index The position at which to add the child or -1 to add last.
         */
        public void addChild(ViewTree child, int index) {
            if (mChildren == null) {
                mChildren = new ArrayList<>();
            }
            child.createTree();

            if (index == INSERT_AT_END_INDEX) {
                mChildren.add(child);
                return;
            }

            mChildren.add(index, child);
        }

        private void addViewChild(View v) {
            // ViewTree only contains Views which can be found using findViewById.
            // If isRootNamespace is true, this view is skipped.
            // @see ViewGroup#findViewTraversal(int)
            if (v.isRootNamespace()) {
                return;
            }
            final ViewTree target;

            // If the view has a valid id, i.e., if can be found using findViewById, add it to the
            // tree, otherwise skip this view and add its children instead.
            if (v.getId() != 0) {
                ViewTree tree = new ViewTree(v);
                mChildren.add(tree);
                target = tree;
            } else {
                target = this;
            }

            if (v instanceof ViewGroup) {
                if (target.mChildren == null) {
                    target.mChildren = new ArrayList<>();
                    ViewGroup vg = (ViewGroup) v;
                    int count = vg.getChildCount();
                    for (int i = 0; i < count; i++) {
                        target.addViewChild(vg.getChildAt(i));
                    }
                }
            }
        }
    }
}