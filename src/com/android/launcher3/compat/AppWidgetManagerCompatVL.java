/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.launcher4.compat;

import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.annotation.Nullable;

import com.android.launcher4.LauncherAppWidgetInfo;
import com.android.launcher4.LauncherAppWidgetProviderInfo;
import com.android.launcher4.config.FeatureFlags;
import com.android.launcher4.util.ComponentKey;
import com.android.launcher4.util.PackageUserKey;
import com.android.launcher4.widget.custom.CustomWidgetParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

class AppWidgetManagerCompatVL extends AppWidgetManagerCompat {

    private final UserManager mUserManager;

    AppWidgetManagerCompatVL(Context context) {
        super(context);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public List<AppWidgetProviderInfo> getAllProviders(@Nullable PackageUserKey packageUser) {
        if (FeatureFlags.GO_DISABLE_WIDGETS) {
            return Collections.emptyList();
        }
        if (packageUser == null) {
            ArrayList<AppWidgetProviderInfo> providers = new ArrayList<AppWidgetProviderInfo>();
            for (UserHandle user : mUserManager.getUserProfiles()) {
                providers.addAll(mAppWidgetManager.getInstalledProvidersForProfile(user));
            }

            if (FeatureFlags.ENABLE_CUSTOM_WIDGETS) {
                providers.addAll(CustomWidgetParser.getCustomWidgets(mContext));
            }
            return providers;
        }
        // Only get providers for the given package/user.
        List<AppWidgetProviderInfo> providers = new ArrayList<>(mAppWidgetManager
                .getInstalledProvidersForProfile(packageUser.mUser));
        Iterator<AppWidgetProviderInfo> iterator = providers.iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().provider.getPackageName().equals(packageUser.mPackageName)) {
                iterator.remove();
            }
        }

        if (FeatureFlags.ENABLE_CUSTOM_WIDGETS && Process.myUserHandle().equals(packageUser.mUser)
                && mContext.getPackageName().equals(packageUser.mPackageName)) {
            providers.addAll(CustomWidgetParser.getCustomWidgets(mContext));
        }
        return providers;
    }

    @Override
    public boolean bindAppWidgetIdIfAllowed(int appWidgetId, AppWidgetProviderInfo info,
            Bundle options) {
        if (FeatureFlags.GO_DISABLE_WIDGETS) {
            return false;
        }

        if (FeatureFlags.ENABLE_CUSTOM_WIDGETS
                && appWidgetId <= LauncherAppWidgetInfo.CUSTOM_WIDGET_ID) {
            return true;
        }
        return mAppWidgetManager.bindAppWidgetIdIfAllowed(
                appWidgetId, info.getProfile(), info.provider, options);
    }

    @Override
    public LauncherAppWidgetProviderInfo findProvider(ComponentName provider, UserHandle user) {
        if (FeatureFlags.GO_DISABLE_WIDGETS) {
            return null;
        }
        for (AppWidgetProviderInfo info :
                getAllProviders(new PackageUserKey(provider.getPackageName(), user))) {
            if (info.provider.equals(provider)) {
                return LauncherAppWidgetProviderInfo.fromProviderInfo(mContext, info);
            }
        }

        if (FeatureFlags.ENABLE_CUSTOM_WIDGETS && Process.myUserHandle().equals(user)) {
            for (LauncherAppWidgetProviderInfo info :
                    CustomWidgetParser.getCustomWidgets(mContext)) {
                if (info.provider.equals(provider)) {
                    return info;
                }
            }
        }
        return null;
    }

    @Override
    public HashMap<ComponentKey, AppWidgetProviderInfo> getAllProvidersMap() {
        HashMap<ComponentKey, AppWidgetProviderInfo> result = new HashMap<>();
        if (FeatureFlags.GO_DISABLE_WIDGETS) {
            return result;
        }
        for (UserHandle user : mUserManager.getUserProfiles()) {
            for (AppWidgetProviderInfo info :
                    mAppWidgetManager.getInstalledProvidersForProfile(user)) {
                result.put(new ComponentKey(info.provider, user), info);
            }
        }

        if (FeatureFlags.ENABLE_CUSTOM_WIDGETS) {
            for (LauncherAppWidgetProviderInfo info :
                    CustomWidgetParser.getCustomWidgets(mContext)) {
                result.put(new ComponentKey(info.provider, info.getProfile()), info);
            }
        }
        return result;
    }
}
