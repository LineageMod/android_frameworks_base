/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.systemui;

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_NONE;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;
import static android.provider.Settings.Secure.STATUS_BAR_BATTERY_STYLE;

import android.animation.ArgbEvaluator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.settingslib.graph.ThemedBatteryDrawable;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.Utils.DisableStateTracker;
import com.android.systemui.R;

import java.text.NumberFormat;

public class BatteryMeterView extends LinearLayout implements
        BatteryStateChangeCallback, Tunable, DarkReceiver, ConfigurationListener {

    private ThemedBatteryDrawable mDrawable;
    private ImageView mBatteryIconView;
    private final CurrentUserTracker mUserTracker;
    private TextView mBatteryPercentView;

    private boolean mCharging;
    //private int mBatteryStyle = BATTERY_STYLE_PORTRAIT;

    private static final String FONT_FAMILY = "sans-serif-medium";
    private BatteryController mBatteryController;
    private SettingObserver mSettingObserver;
    private int mTextColor;
    private int mLevel;
    private boolean misQsbHeader;
    private int mShowPercent;
    private boolean mShowPercentAvailable;
    private boolean mCharging;
    private boolean mPowerSave;

    private int mDarkModeSingleToneColor;
    private int mDarkModeBackgroundColor;
    private int mDarkModeFillColor;

    private int mLightModeSingleToneColor;
    private int mLightModeBackgroundColor;
    private int mLightModeFillColor;
    private float mDarkIntensity;
    private int mUser;

    private final Context mContext;
    private final int mFrameColor;

    /**
     * Whether we should use colors that adapt based on wallpaper/the scrim behind quick settings.
     */
    private boolean mUseWallpaperTextColors;

    private int mNonAdaptedSingleToneColor;
    private int mNonAdaptedForegroundColor;
    private int mNonAdaptedBackgroundColor;

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mFrameColor = frameColor;
        mDrawable = new ThemedBatteryDrawable(context, frameColor);
        atts.recycle();

        mSettingObserver = new SettingObserver(new Handler(context.getMainLooper()));
        mShowPercentAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_battery_percentage_setting_available);


        addOnAttachStateChangeListener(
                new DisableStateTracker(DISABLE_NONE, DISABLE2_SYSTEM_ICONS));

        mBatteryIconView = new ImageView(context);
        mBatteryIconView.setImageDrawable(mDrawable);
        final MarginLayoutParams mlp = new MarginLayoutParams(
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
        mlp.setMargins(0, 0, 0,
                getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);

        updateShowPercent();
        setColorsFromContext(context);
        // Init to not dark at all.
        onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mUser = newUserId;
                getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
                getContext().getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(SHOW_BATTERY_PERCENT), false, mSettingObserver,
                        newUserId);
            }
        };

        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setForceShowPercent(boolean show) {
        updateShowPercent();
    }

    /**
     * Sets whether the battery meter view uses the wallpaperTextColor. If we're not using it, we'll
     * revert back to dark-mode-based/tinted colors.
     *
     * @param shouldUseWallpaperTextColor whether we should use wallpaperTextColor for all
     *                                    components
     */
    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor) {
        useWallpaperTextColor(shouldUseWallpaperTextColor, false);
    }

    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor, boolean force) {
        if (!force && shouldUseWallpaperTextColor == mUseWallpaperTextColors) {
            return;
        }

        mUseWallpaperTextColors = shouldUseWallpaperTextColor;

        if (mUseWallpaperTextColors) {
            updateColors(
                    Utils.getColorAttr(mContext, R.attr.wallpaperTextColor),
                    Utils.getColorAttr(mContext, R.attr.wallpaperTextColorSecondary),
                    Utils.getColorAttr(mContext, R.attr.wallpaperTextColor));
        } else {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor, mNonAdaptedSingleToneColor);
        }
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        Context dualToneDarkTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.darkIconTheme));
        Context dualToneLightTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.lightIconTheme));
        mDarkModeSingleToneColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.singleToneColor);
        mDarkModeBackgroundColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.backgroundColor);
        mDarkModeFillColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.fillColor);
        mLightModeSingleToneColor = Utils.getColorAttr(dualToneLightTheme, R.attr.singleToneColor);
        mLightModeBackgroundColor = Utils.getColorAttr(dualToneLightTheme, R.attr.backgroundColor);
        mLightModeFillColor = Utils.getColorAttr(dualToneLightTheme, R.attr.fillColor);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (STATUS_BAR_BATTERY_STYLE.equals(key)) {
            updateBatteryStyle(newValue);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        mUser = ActivityManager.getCurrentUser();
        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT), false, mSettingObserver, mUser);
        updateShowPercent();
        Dependency.get(TunerService.class).addTunable(this, STATUS_BAR_BATTERY_STYLE);
        Dependency.get(ConfigurationController.class).addCallback(this);
        mUserTracker.startTracking();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mUserTracker.stopTracking();
        mBatteryController.removeCallback(this);
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
        Dependency.get(TunerService.class).removeTunable(this);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {

        // text battery will always show percentage
        if (isCircleBattery()
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT) {
            setForceShowPercent(pluggedIn);
            // mDrawable.setCharging(pluggedIn) will invalidate the view
        }

        mDrawable.setBatteryLevel(level);
        mDrawable.setCharging(pluggedIn);
        mLevel = level;
        if (mCharging != pluggedIn) {
            mCharging = pluggedIn;
            updateShowPercent();
        }
        updatePercentText();
        setContentDescription(
                getContext().getString(charging ? R.string.accessibility_battery_level_charging
                        : R.string.accessibility_battery_level, level));
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mDrawable.setPowerSaveEnabled(isPowerSave);
        if (mPowerSave != isPowerSave) {
            mPowerSave = isPowerSave;
            updateShowPercent();
        }
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    private void updatePercentText() {
        Typeface tf = Typeface.create(FONT_FAMILY, Typeface.NORMAL);
		if (mBatteryPercentView != null) {
            // Use the high voltage symbol ⚡ (u26A1 unicode) but prevent the system
            // to load its emoji colored variant with the uFE0E flag
            String bolt = "\u26A1\uFE0E";
            CharSequence mChargeIndicator =
                    mCharging && getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_TEXT
                    ? (bolt + " ") : "";
            mBatteryPercentView.setText(mChargeIndicator + NumberFormat.getPercentInstance().format(mLevel / 100f).replace('\u00A0',' ').replace('\u2007',' ').replace('\u202F',' ').replace('\u2060',' ').replace(" ",""));
			mBatteryPercentView.setTypeface(tf);
        }
    }

    private void updateShowPercent() {
        final boolean showing = mBatteryPercentView != null;
        int percentageStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                SHOW_BATTERY_PERCENT, 0, mUser);
        mShowPercent = percentageStyle;
        boolean showAnyway = alwaysShowPercentage() || mPowerSave || mCharging;
        if (!showAnyway
                && getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_HIDDEN) {
            // don't show percentage
            percentageStyle = 0;
        }
        if (showAnyway) percentageStyle = 1; // Default view
        switch (percentageStyle) {
            case 1:
                if (!showing) {
                    mBatteryPercentView = loadPercentView();
                    if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
                    updatePercentText();
                    addView(mBatteryPercentView,
                            //0,
                            new ViewGroup.LayoutParams(
                                    LayoutParams.WRAP_CONTENT,
                                    LayoutParams.MATCH_PARENT));
                }
                mDrawable.setShowPercent(false);
                break;
            case 2:
                if (showing) {
                    removePercentageView();
                }
                mDrawable.setShowPercent(true);
                break;
            default:
                if (showing) {
                    removePercentageView();
                }
                mDrawable.setShowPercent(false);
                break;
        }

        if (mBatteryPercentView != null) {
            final Resources res = getContext().getResources();
            final int startPadding = res.getDimensionPixelSize(R.dimen.battery_level_padding_start);
            mBatteryPercentView.setPaddingRelative(startPadding, 0, (mDrawable.getMeterStyle() != BatteryMeterDrawableBase.BATTERY_STYLE_TEXT) ? startPadding : 0, 0);
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        scaleBatteryMeterViews();
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    private void scaleBatteryMeterViews() {
        if (mBatteryPercentView != null) {
            FontSizeUtils.updateFontSize(mBatteryPercentView, R.dimen.qs_time_expanded_size);
        }

        if (mBatteryIconView == null) {
            return;
        }

        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));
        scaledLayoutParams.setMargins(0, 0, 0, marginBottom);

        mBatteryIconView.post(() -> mBatteryIconView.setLayoutParams(scaledLayoutParams));
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mDarkIntensity = darkIntensity;

        float intensity = DarkIconDispatcher.isInArea(area, this) ? darkIntensity : 0;
        mNonAdaptedSingleToneColor = getColorForDarkIntensity(
                intensity, mLightModeSingleToneColor, mDarkModeSingleToneColor);
        mNonAdaptedForegroundColor = getColorForDarkIntensity(
                intensity, mLightModeFillColor, mDarkModeFillColor);
        mNonAdaptedBackgroundColor = getColorForDarkIntensity(
                intensity, mLightModeBackgroundColor,mDarkModeBackgroundColor);

        if (!mUseWallpaperTextColors) {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor, mNonAdaptedSingleToneColor);
        }
    }

    private void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = foregroundColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(foregroundColor);
        }
    }

    public void setFillColor(int color) {
        if (mLightModeFillColor == color) {
            return;
        }
        mLightModeFillColor = color;
        onDarkChanged(new Rect(), mDarkIntensity, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent();
        }
    }

    public void isQsbHeader() {
        misQsbHeader = true;
    }

    private boolean alwaysShowPercentage() {
        return getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_TEXT
                || (misQsbHeader
                && (getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_HIDDEN
                || (getMeterStyle() != BatteryMeterDrawableBase.BATTERY_STYLE_HIDDEN
                && mShowPercent == 0/*hidden*/)));
    }

    private void updateBatteryStyle(String styleStr) {
        final int style = styleStr == null ?
                BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT : Integer.parseInt(styleStr);
        mDrawable.setMeterStyle(style);
        useWallpaperTextColor(mUseWallpaperTextColors, true);

        switch (style) {
            case BatteryMeterDrawableBase.BATTERY_STYLE_TEXT:
                if (mBatteryIconView != null) {
                    removeView(mBatteryIconView);
                    mBatteryIconView = null;
                }
                break;
            case BatteryMeterDrawableBase.BATTERY_STYLE_HIDDEN:
                if (mBatteryIconView != null) {
                    removeView(mBatteryIconView);
                    mBatteryIconView = null;
                }
                removePercentageView();
                break;
            default:
                removePercentageView();
                if (mBatteryIconView == null) {
                    mBatteryIconView = new ImageView(mContext);
                    mBatteryIconView.setImageDrawable(mDrawable);
                    final MarginLayoutParams mlp = new MarginLayoutParams(
                            getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                            getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
                    mlp.setMargins(0, 0, 0,
                            getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
                    addView(mBatteryIconView, mlp);
                }
                break;
        }

        scaleBatteryMeterViews();
        updateShowPercent();
        updatePercentText();
    }

    private boolean isCircleBattery() {
        return getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_CIRCLE
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_DOTTED_CIRCLE;
    }

    private int getMeterStyle() {
        return mDrawable.getMeterStyle();
    }

    private void removePercentageView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
    }
}
