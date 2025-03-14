/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.launcher4.logging;

import static com.android.launcher4.logging.LoggerUtils.newAction;
import static com.android.launcher4.logging.LoggerUtils.newCommandAction;
import static com.android.launcher4.logging.LoggerUtils.newContainerTarget;
import static com.android.launcher4.logging.LoggerUtils.newControlTarget;
import static com.android.launcher4.logging.LoggerUtils.newDropTarget;
import static com.android.launcher4.logging.LoggerUtils.newItemTarget;
import static com.android.launcher4.logging.LoggerUtils.newLauncherEvent;
import static com.android.launcher4.logging.LoggerUtils.newTarget;
import static com.android.launcher4.logging.LoggerUtils.newTouchAction;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import com.android.launcher4.DeviceProfile;
import com.android.launcher4.DropTarget;
import com.android.launcher4.ItemInfo;
import com.android.launcher4.R;
import com.android.launcher4.Utilities;
import com.android.launcher4.config.FeatureFlags;
import com.android.launcher4.userevent.nano.LauncherLogProto;
import com.android.launcher4.userevent.nano.LauncherLogProto.Action;
import com.android.launcher4.userevent.nano.LauncherLogProto.ContainerType;
import com.android.launcher4.userevent.nano.LauncherLogProto.LauncherEvent;
import com.android.launcher4.userevent.nano.LauncherLogProto.Target;
import com.android.launcher4.util.ComponentKey;
import com.android.launcher4.util.InstantAppResolver;
import com.android.launcher4.util.LogConfig;

import java.util.Locale;
import java.util.UUID;

/**
 * Manages the creation of {@link LauncherEvent}.
 * To debug this class, execute following command before side loading a new apk.
 *
 * $ adb shell setprop log.tag.UserEvent VERBOSE
 */
public class UserEventDispatcher {

    private final static int MAXIMUM_VIEW_HIERARCHY_LEVEL = 5;

    private static final String TAG = "UserEvent";
    private static final boolean IS_VERBOSE =
            FeatureFlags.IS_DOGFOOD_BUILD && Utilities.isPropertyEnabled(LogConfig.USEREVENT);
    private static final String UUID_STORAGE = "uuid";

    public static UserEventDispatcher newInstance(Context context, DeviceProfile dp,
            UserEventDelegate delegate) {
        SharedPreferences sharedPrefs = Utilities.getDevicePrefs(context);
        String uuidStr = sharedPrefs.getString(UUID_STORAGE, null);
        if (uuidStr == null) {
            uuidStr = UUID.randomUUID().toString();
            sharedPrefs.edit().putString(UUID_STORAGE, uuidStr).apply();
        }
        UserEventDispatcher ued = Utilities.getOverrideObject(UserEventDispatcher.class,
                context.getApplicationContext(), R.string.user_event_dispatcher_class);
        ued.mDelegate = delegate;
        ued.mIsInLandscapeMode = dp.isVerticalBarLayout();
        ued.mIsInMultiWindowMode = dp.isMultiWindowMode;
        ued.mUuidStr = uuidStr;
        ued.mInstantAppResolver = InstantAppResolver.newInstance(context);
        return ued;
    }

    public static UserEventDispatcher newInstance(Context context, DeviceProfile dp) {
        return newInstance(context, dp, null);
    }

    public interface UserEventDelegate {
        void modifyUserEvent(LauncherEvent event);
    }

    /**
     * Implemented by containers to provide a container source for a given child.
     */
    public interface LogContainerProvider {

        /**
         * Copies data from the source to the destination proto.
         *
         * @param v            source of the data
         * @param info         source of the data
         * @param target       dest of the data
         * @param targetParent dest of the data
         */
        void fillInLogContainerData(View v, ItemInfo info, Target target, Target targetParent);
    }

    /**
     * Recursively finds the parent of the given child which implements IconLogInfoProvider
     */
    public static LogContainerProvider getLaunchProviderRecursive(@Nullable View v) {
        ViewParent parent;
        if (v != null) {
            parent = v.getParent();
        } else {
            return null;
        }

        // Optimization to only check up to 5 parents.
        int count = MAXIMUM_VIEW_HIERARCHY_LEVEL;
        while (parent != null && count-- > 0) {
            if (parent instanceof LogContainerProvider) {
                return (LogContainerProvider) parent;
            } else {
                parent = parent.getParent();
            }
        }
        return null;
    }

    private boolean mSessionStarted;
    private long mElapsedContainerMillis;
    private long mElapsedSessionMillis;
    private long mActionDurationMillis;
    private boolean mIsInMultiWindowMode;
    private boolean mIsInLandscapeMode;
    private String mUuidStr;
    protected InstantAppResolver mInstantAppResolver;
    private boolean mAppOrTaskLaunch;
    private UserEventDelegate mDelegate;

    //                      APP_ICON    SHORTCUT    WIDGET
    // --------------------------------------------------------------
    // packageNameHash      required    optional    required
    // componentNameHash    required                required
    // intentHash                       required
    // --------------------------------------------------------------

    /**
     * Fills in the container data on the given event if the given view is not null.
     * @return whether container data was added.
     */
    protected boolean fillInLogContainerData(LauncherEvent event, @Nullable View v) {
        // Fill in grid(x,y), pageIndex of the child and container type of the parent
        LogContainerProvider provider = getLaunchProviderRecursive(v);
        if (v == null || !(v.getTag() instanceof ItemInfo) || provider == null) {
            return false;
        }
        ItemInfo itemInfo = (ItemInfo) v.getTag();
        provider.fillInLogContainerData(v, itemInfo, event.srcTarget[0], event.srcTarget[1]);
        return true;
    }

    public void logAppLaunch(View v, Intent intent) {
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Touch.TAP),
                newItemTarget(v, mInstantAppResolver), newTarget(Target.Type.CONTAINER));

        if (fillInLogContainerData(event, v)) {
            if (mDelegate != null) {
                mDelegate.modifyUserEvent(event);
            }
            fillIntentInfo(event.srcTarget[0], intent);
        }
        dispatchUserEvent(event, intent);
        mAppOrTaskLaunch = true;
    }

    public void logActionTip(int actionType, int viewType) { }

    public void logTaskLaunchOrDismiss(int action, int direction, int taskIndex,
            ComponentKey componentKey) {
        LauncherEvent event = newLauncherEvent(newTouchAction(action), // TAP or SWIPE or FLING
                newTarget(Target.Type.ITEM));
        if (action == Action.Touch.SWIPE || action == Action.Touch.FLING) {
            // Direction DOWN means the task was launched, UP means it was dismissed.
            event.action.dir = direction;
        }
        event.srcTarget[0].itemType = LauncherLogProto.ItemType.TASK;
        event.srcTarget[0].pageIndex = taskIndex;
        fillComponentInfo(event.srcTarget[0], componentKey.componentName);
        dispatchUserEvent(event, null);
        mAppOrTaskLaunch = true;
    }

    protected void fillIntentInfo(Target target, Intent intent) {
        target.intentHash = intent.hashCode();
        fillComponentInfo(target, intent.getComponent());
    }

    private void fillComponentInfo(Target target, ComponentName cn) {
        if (cn != null) {
            target.packageNameHash = (mUuidStr + cn.getPackageName()).hashCode();
            target.componentHash = (mUuidStr + cn.flattenToString()).hashCode();
        }
    }

    public void logNotificationLaunch(View v, PendingIntent intent) {
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Touch.TAP),
                newItemTarget(v, mInstantAppResolver), newTarget(Target.Type.CONTAINER));
        if (fillInLogContainerData(event, v)) {
            event.srcTarget[0].packageNameHash = (mUuidStr + intent.getCreatorPackage()).hashCode();
        }
        dispatchUserEvent(event, null);
    }

    public void logActionCommand(int command, Target srcTarget) {
        logActionCommand(command, srcTarget, null);
    }

    public void logActionCommand(int command, int srcContainerType, int dstContainerType) {
        logActionCommand(command, newContainerTarget(srcContainerType),
                dstContainerType >=0 ? newContainerTarget(dstContainerType) : null);
    }

    public void logActionCommand(int command, Target srcTarget, Target dstTarget) {
        LauncherEvent event = newLauncherEvent(newCommandAction(command), srcTarget);
        if (command == Action.Command.STOP) {
            if (mAppOrTaskLaunch || !mSessionStarted) {
                mSessionStarted = false;
                return;
            }
        }

        if (dstTarget != null) {
            event.destTarget = new Target[1];
            event.destTarget[0] = dstTarget;
            event.action.isStateChange = true;
        }
        dispatchUserEvent(event, null);
    }

    /**
     * TODO: Make this function work when a container view is passed as the 2nd param.
     */
    public void logActionCommand(int command, View itemView, int srcContainerType) {
        LauncherEvent event = newLauncherEvent(newCommandAction(command),
                newItemTarget(itemView, mInstantAppResolver), newTarget(Target.Type.CONTAINER));

        if (fillInLogContainerData(event, itemView)) {
            // TODO: Remove the following two lines once fillInLogContainerData can take in a
            // container view.
            event.srcTarget[0].type = Target.Type.CONTAINER;
            event.srcTarget[0].containerType = srcContainerType;
        }
        dispatchUserEvent(event, null);
    }

    public void logActionOnControl(int action, int controlType) {
        logActionOnControl(action, controlType, null, -1);
    }

    public void logActionOnControl(int action, int controlType, int parentContainerType) {
        logActionOnControl(action, controlType, null, parentContainerType);
    }

    public void logActionOnControl(int action, int controlType, @Nullable View controlInContainer) {
        logActionOnControl(action, controlType, controlInContainer, -1);
    }

    public void logActionOnControl(int action, int controlType, int parentContainer,
                                   int grandParentContainer){
        LauncherEvent event = newLauncherEvent(newTouchAction(action),
                newControlTarget(controlType),
                newContainerTarget(parentContainer),
                newContainerTarget(grandParentContainer));
        dispatchUserEvent(event, null);
    }

    public void logActionOnControl(int action, int controlType, @Nullable View controlInContainer,
                                   int parentContainerType) {
        final LauncherEvent event = (controlInContainer == null && parentContainerType < 0)
                ? newLauncherEvent(newTouchAction(action), newTarget(Target.Type.CONTROL))
                : newLauncherEvent(newTouchAction(action), newTarget(Target.Type.CONTROL),
                        newTarget(Target.Type.CONTAINER));
        event.srcTarget[0].controlType = controlType;
        if (controlInContainer != null) {
            fillInLogContainerData(event, controlInContainer);
        }
        if (parentContainerType >= 0) {
            event.srcTarget[1].containerType = parentContainerType;
        }
        if (action == Action.Touch.DRAGDROP) {
            event.actionDurationMillis = SystemClock.uptimeMillis() - mActionDurationMillis;
        }
        dispatchUserEvent(event, null);
    }

    public void logActionTapOutside(Target target) {
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Type.TOUCH),
                target);
        event.action.isOutside = true;
        dispatchUserEvent(event, null);
    }

    public void logActionBounceTip(int containerType) {
        LauncherEvent event = newLauncherEvent(newAction(Action.Type.TIP),
                newContainerTarget(containerType));
        event.srcTarget[0].tipType = LauncherLogProto.TipType.BOUNCE;
        dispatchUserEvent(event, null);
    }

    public void logActionOnContainer(int action, int dir, int containerType) {
        logActionOnContainer(action, dir, containerType, 0);
    }

    public void logActionOnContainer(int action, int dir, int containerType, int pageIndex) {
        LauncherEvent event = newLauncherEvent(newTouchAction(action),
                newContainerTarget(containerType));
        event.action.dir = dir;
        event.srcTarget[0].pageIndex = pageIndex;
        dispatchUserEvent(event, null);
    }

    /**
     * Used primarily for swipe up and down when state changes when swipe up happens from the
     * navbar bezel, the {@param srcChildContainerType} is NAVBAR and
     * {@param srcParentContainerType} is either one of the two
     * (1) WORKSPACE: if the launcher is the foreground activity
     * (2) APP: if another app was the foreground activity
     */
    public void logStateChangeAction(int action, int dir, int srcChildTargetType,
                                     int srcParentContainerType, int dstContainerType,
                                     int pageIndex) {
        LauncherEvent event;
        if (srcChildTargetType == LauncherLogProto.ItemType.TASK) {
            event = newLauncherEvent(newTouchAction(action),
                    newItemTarget(srcChildTargetType),
                    newContainerTarget(srcParentContainerType));
        } else {
            event = newLauncherEvent(newTouchAction(action),
                    newContainerTarget(srcChildTargetType),
                    newContainerTarget(srcParentContainerType));
        }
        event.destTarget = new Target[1];
        event.destTarget[0] = newContainerTarget(dstContainerType);
        event.action.dir = dir;
        event.action.isStateChange = true;
        event.srcTarget[0].pageIndex = pageIndex;
        dispatchUserEvent(event, null);
        resetElapsedContainerMillis("state changed");
    }

    public void logActionOnItem(int action, int dir, int itemType) {
        Target itemTarget = newTarget(Target.Type.ITEM);
        itemTarget.itemType = itemType;
        LauncherEvent event = newLauncherEvent(newTouchAction(action), itemTarget);
        event.action.dir = dir;
        dispatchUserEvent(event, null);
    }

    public void logDeepShortcutsOpen(View icon) {
        LogContainerProvider provider = getLaunchProviderRecursive(icon);
        if (icon == null || !(icon.getTag() instanceof ItemInfo)) {
            return;
        }
        ItemInfo info = (ItemInfo) icon.getTag();
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Touch.LONGPRESS),
                newItemTarget(info, mInstantAppResolver), newTarget(Target.Type.CONTAINER));
        provider.fillInLogContainerData(icon, info, event.srcTarget[0], event.srcTarget[1]);
        dispatchUserEvent(event, null);

        resetElapsedContainerMillis("deep shortcut open");
    }

    /* Currently we are only interested in whether this event happens or not and don't
    * care about which screen moves to where. */
    public void logOverviewReorder() {
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Touch.DRAGDROP),
                newContainerTarget(ContainerType.WORKSPACE),
                newContainerTarget(ContainerType.OVERVIEW));
        dispatchUserEvent(event, null);
    }

    public void logDragNDrop(DropTarget.DragObject dragObj, View dropTargetAsView) {
        LauncherEvent event = newLauncherEvent(newTouchAction(Action.Touch.DRAGDROP),
                newItemTarget(dragObj.originalDragInfo, mInstantAppResolver),
                newTarget(Target.Type.CONTAINER));
        event.destTarget = new Target[] {
                newItemTarget(dragObj.originalDragInfo, mInstantAppResolver),
                newDropTarget(dropTargetAsView)
        };

        dragObj.dragSource.fillInLogContainerData(null, dragObj.originalDragInfo,
                event.srcTarget[0], event.srcTarget[1]);

        if (dropTargetAsView instanceof LogContainerProvider) {
            ((LogContainerProvider) dropTargetAsView).fillInLogContainerData(null,
                    dragObj.dragInfo, event.destTarget[0], event.destTarget[1]);

        }
        event.actionDurationMillis = SystemClock.uptimeMillis() - mActionDurationMillis;
        dispatchUserEvent(event, null);
    }

    /**
     * Currently logs following containers: workspace, allapps, widget tray.
     * @param reason
     */
    public final void resetElapsedContainerMillis(String reason) {
        mElapsedContainerMillis = SystemClock.uptimeMillis();
        if (!IS_VERBOSE) {
            return;
        }
        Log.d(TAG, "resetElapsedContainerMillis reason=" + reason);

    }

    public final void startSession() {
        mSessionStarted = true;
        mElapsedSessionMillis = SystemClock.uptimeMillis();
        mElapsedContainerMillis = SystemClock.uptimeMillis();
    }

    public final void resetActionDurationMillis() {
        mActionDurationMillis = SystemClock.uptimeMillis();
    }

    public void dispatchUserEvent(LauncherEvent ev, Intent intent) {
        mAppOrTaskLaunch = false;
        ev.isInLandscapeMode = mIsInLandscapeMode;
        ev.isInMultiWindowMode = mIsInMultiWindowMode;
        ev.elapsedContainerMillis = SystemClock.uptimeMillis() - mElapsedContainerMillis;
        ev.elapsedSessionMillis = SystemClock.uptimeMillis() - mElapsedSessionMillis;

        if (!IS_VERBOSE) {
            return;
        }
        String log = "\n-----------------------------------------------------"
                + "\naction:" + LoggerUtils.getActionStr(ev.action);
        if (ev.srcTarget != null && ev.srcTarget.length > 0) {
            log += "\n Source " + getTargetsStr(ev.srcTarget);
        }
        if (ev.destTarget != null && ev.destTarget.length > 0) {
            log += "\n Destination " + getTargetsStr(ev.destTarget);
        }
        log += String.format(Locale.US,
                "\n Elapsed container %d ms, session %d ms, action %d ms",
                ev.elapsedContainerMillis,
                ev.elapsedSessionMillis,
                ev.actionDurationMillis);
        log += "\n isInLandscapeMode " + ev.isInLandscapeMode;
        log += "\n isInMultiWindowMode " + ev.isInMultiWindowMode;
        log += "\n\n";
        Log.d(TAG, log);
    }

    private static String getTargetsStr(Target[] targets) {
        String result = "child:" + LoggerUtils.getTargetStr(targets[0]);
        for (int i = 1; i < targets.length; i++) {
            result += "\tparent:" + LoggerUtils.getTargetStr(targets[i]);
        }
        return result;
    }
}
