package com.android.launcher4.model;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.os.Process;
import android.os.UserHandle;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.provider.ProviderTestRule;

import com.android.launcher4.AllAppsList;
import com.android.launcher4.AppFilter;
import com.android.launcher4.AppInfo;
import com.android.launcher4.IconCache;
import com.android.launcher4.InvariantDeviceProfile;
import com.android.launcher4.ItemInfo;
import com.android.launcher4.LauncherAppState;
import com.android.launcher4.LauncherModel;
import com.android.launcher4.LauncherModel.Callbacks;
import com.android.launcher4.LauncherModel.ModelUpdateTask;
import com.android.launcher4.LauncherProvider;
import com.android.launcher4.graphics.BitmapInfo;
import com.android.launcher4.util.ComponentKey;
import com.android.launcher4.util.Provider;
import com.android.launcher4.util.TestLauncherProvider;

import org.junit.Before;
import org.junit.Rule;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Base class for writing tests for Model update tasks.
 */
public class BaseModelUpdateTaskTestCase {

    @Rule
    public ProviderTestRule mProviderRule =
            new ProviderTestRule.Builder(TestLauncherProvider.class, LauncherProvider.AUTHORITY)
                    .build();

    public final HashMap<Class, HashMap<String, Field>> fieldCache = new HashMap<>();

    public Context targetContext;
    public UserHandle myUser;

    public InvariantDeviceProfile idp;
    public LauncherAppState appState;
    public LauncherModel model;
    public ModelWriter modelWriter;
    public MyIconCache iconCache;

    public BgDataModel bgDataModel;
    public AllAppsList allAppsList;
    public Callbacks callbacks;

    @Before
    public void setUp() throws Exception {
        callbacks = mock(Callbacks.class);
        appState = mock(LauncherAppState.class);
        model = mock(LauncherModel.class);
        modelWriter = mock(ModelWriter.class);

        when(appState.getModel()).thenReturn(model);
        when(model.getWriter(anyBoolean(), anyBoolean())).thenReturn(modelWriter);
        when(model.getCallback()).thenReturn(callbacks);

        myUser = Process.myUserHandle();

        bgDataModel = new BgDataModel();
        targetContext = new ContextWrapper(InstrumentationRegistry.getTargetContext()) {
            @Override
            public ContentResolver getContentResolver() {
                return mProviderRule.getResolver();
            }
        };
        idp = new InvariantDeviceProfile();
        iconCache = new MyIconCache(targetContext, idp);

        allAppsList = new AllAppsList(iconCache, new AppFilter());

        when(appState.getIconCache()).thenReturn(iconCache);
        when(appState.getInvariantDeviceProfile()).thenReturn(idp);
        when(appState.getContext()).thenReturn(targetContext);

    }

    /**
     * Synchronously executes the task and returns all the UI callbacks posted.
     */
    public List<Runnable> executeTaskForTest(ModelUpdateTask task) throws Exception {
        when(model.isModelLoaded()).thenReturn(true);

        Executor mockExecutor = mock(Executor.class);

        task.init(appState, model, bgDataModel, allAppsList, mockExecutor);
        task.run();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockExecutor, atLeast(0)).execute(captor.capture());

        return captor.getAllValues();
    }

    /**
     * Initializes mock data for the test.
     */
    public void initializeData(String resourceName) throws Exception {
        Context myContext = InstrumentationRegistry.getContext();
        Resources res = myContext.getResources();
        int id = res.getIdentifier(resourceName, "raw", myContext.getPackageName());
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(res.openRawResource(id)))) {
            String line;
            HashMap<String, Class> classMap = new HashMap<>();
            while((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String[] commands = line.split(" ");
                switch (commands[0]) {
                    case "classMap":
                        classMap.put(commands[1], Class.forName(commands[2]));
                        break;
                    case "bgItem":
                        bgDataModel.addItem(targetContext,
                                (ItemInfo) initItem(classMap.get(commands[1]), commands, 2), false);
                        break;
                    case "allApps":
                        allAppsList.add((AppInfo) initItem(AppInfo.class, commands, 1), null);
                        break;
                }
            }
        }
    }

    private Object initItem(Class clazz, String[] fieldDef, int startIndex) throws Exception {
        HashMap<String, Field> cache = fieldCache.get(clazz);
        if (cache == null) {
            cache = new HashMap<>();
            Class c = clazz;
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    cache.put(f.getName(), f);
                }
                c = c.getSuperclass();
            }
            fieldCache.put(clazz, cache);
        }

        Object item = clazz.newInstance();
        for (int i = startIndex; i < fieldDef.length; i++) {
            String[] fieldData = fieldDef[i].split("=", 2);
            Field f = cache.get(fieldData[0]);
            Class type = f.getType();
            if (type == int.class || type == long.class) {
                f.set(item, Integer.parseInt(fieldData[1]));
            } else if (type == CharSequence.class || type == String.class) {
                f.set(item, fieldData[1]);
            } else if (type == Intent.class) {
                if (!fieldData[1].startsWith("#Intent")) {
                    fieldData[1] = "#Intent;" + fieldData[1] + ";end";
                }
                f.set(item, Intent.parseUri(fieldData[1], 0));
            } else if (type == ComponentName.class) {
                f.set(item, ComponentName.unflattenFromString(fieldData[1]));
            } else {
                throw new Exception("Added parsing logic for "
                        + f.getName() + " of type " + f.getType());
            }
        }
        return item;
    }

    public static class MyIconCache extends IconCache {

        private final HashMap<ComponentKey, CacheEntry> mCache = new HashMap<>();

        public MyIconCache(Context context, InvariantDeviceProfile idp) {
            super(context, idp);
        }

        @Override
        protected CacheEntry cacheLocked(
                @NonNull ComponentName componentName,
                @NonNull Provider<LauncherActivityInfo> infoProvider,
                UserHandle user, boolean usePackageIcon, boolean useLowResIcon) {
            CacheEntry entry = mCache.get(new ComponentKey(componentName, user));
            if (entry == null) {
                entry = new CacheEntry();
                getDefaultIcon(user).applyTo(entry);
            }
            return entry;
        }

        public void addCache(ComponentName key, String title) {
            CacheEntry entry = new CacheEntry();
            entry.icon = newIcon();
            entry.color = Color.RED;
            entry.title = title;
            mCache.put(new ComponentKey(key, Process.myUserHandle()), entry);
        }

        public Bitmap newIcon() {
            return Bitmap.createBitmap(1, 1, Config.ARGB_8888);
        }

        @Override
        protected BitmapInfo makeDefaultIcon(UserHandle user) {
            return BitmapInfo.fromBitmap(newIcon());
        }
    }
}
