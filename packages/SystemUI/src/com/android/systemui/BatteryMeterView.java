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

import android.animation.ArgbEvaluator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
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
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.text.NumberFormat;

public class BatteryMeterView extends LinearLayout implements
        BatteryStateChangeCallback, Tunable, DarkReceiver, ConfigurationListener {

    private BatteryMeterDrawableBase mDrawable;
    private final String mSlotBattery;
    private ImageView mBatteryIconView;
    private TextView mBatteryPercentView;

    private BatteryController mBatteryController;
    private int mTextColor;
    private int mLevel;
    private boolean mForceShowPercent;

    private int mDarkModeBackgroundColor;
    private int mDarkModeFillColor;

    private int mLightModeBackgroundColor;
    private int mLightModeFillColor;
    private float mDarkIntensity;
    private int mUser;

    private boolean mShowBatteryImage;
    private int mShowBatteryPercent;
    private int mStyle = BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT;
    private boolean mCharging;
    private int mTextChargingSymbol;

    private static final String SHOW_BATTERY_IMAGE =
            "system:" + Settings.System.SHOW_BATTERY_IMAGE;
    private static final String SHOW_BATTERY_PERCENT =
            "system:" + Settings.System.SHOW_BATTERY_PERCENT;
    private static final String STATUS_BAR_BATTERY_STYLE =
            "system:" + Settings.System.STATUS_BAR_BATTERY_STYLE;
    private static final String TEXT_CHARGING_SYMBOL =
            "system:" + Settings.System.TEXT_CHARGING_SYMBOL;

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mDrawable = new BatteryMeterDrawableBase(context, frameColor);
        atts.recycle();

        mSlotBattery = context.getString(
                com.android.internal.R.string.status_bar_battery);

        Context dualToneDarkTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.darkIconTheme));
        Context dualToneLightTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.lightIconTheme));
        mDarkModeBackgroundColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.backgroundColor);
        mDarkModeFillColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.fillColor);
        mLightModeBackgroundColor = Utils.getColorAttr(dualToneLightTheme, R.attr.backgroundColor);
        mLightModeFillColor = Utils.getColorAttr(dualToneLightTheme, R.attr.fillColor);

        // Init to not dark at all.
        onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    public void setForceShowPercent(boolean show) {
        mForceShowPercent = show;
        updateShowPercent();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case SHOW_BATTERY_IMAGE:
                mShowBatteryImage =
                        newValue == null || Integer.parseInt(newValue) != 0;
                updateShowImage();
                break;
            case STATUS_BAR_BATTERY_STYLE:
                mStyle =
                        newValue == null ? BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT : Integer.parseInt(newValue);
                mDrawable.setMeterStyle(mStyle);
                reloadImage();
                break;
            case SHOW_BATTERY_PERCENT:
                mShowBatteryPercent =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                break;
            case TEXT_CHARGING_SYMBOL:
                mTextChargingSymbol =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                break;
            default:
                break;
         }
         updateShowPercent();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        mUser = ActivityManager.getCurrentUser();
        Dependency.get(TunerService.class).addTunable(this,
                SHOW_BATTERY_IMAGE,
                SHOW_BATTERY_PERCENT,
                STATUS_BAR_BATTERY_STYLE,
                TEXT_CHARGING_SYMBOL);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBatteryController.removeCallback(this);
        Dependency.get(TunerService.class).removeTunable(this);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setForceShowPercent(pluggedIn);
        mCharging = pluggedIn;
        mDrawable.setBatteryLevel(level);
        mDrawable.setCharging(pluggedIn);
        mLevel = level;
        updateShowPercent();
        setContentDescription(
                getContext().getString(charging ? R.string.accessibility_battery_level_charging
                        : R.string.accessibility_battery_level, level));
    }

    private boolean isCircleBattery() {
        return mStyle == BatteryMeterDrawableBase.BATTERY_STYLE_CIRCLE
                || mStyle == BatteryMeterDrawableBase.BATTERY_STYLE_DOTTED_CIRCLE;
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mDrawable.setPowerSave(isPowerSave);
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    private void updatePercentText() {
        if (mBatteryPercentView == null)
            return;

        if (!mCharging || mShowBatteryImage) {
            mBatteryPercentView.setText(
                   NumberFormat.getPercentInstance().format(mLevel / 100f));
        } else {
            switch (mTextChargingSymbol) {
                case 1:
                    mBatteryPercentView.setText("⚡️" +
                           NumberFormat.getPercentInstance().format(mLevel / 100f));
                   break;
                case 2:
                    mBatteryPercentView.setText("~" +
                           NumberFormat.getPercentInstance().format(mLevel / 100f));
                    break;
                default:
                    mBatteryPercentView.setText(
                           NumberFormat.getPercentInstance().format(mLevel / 100f));
                    break;
            }
        }
    }

    private void updateShowPercent() {
        final boolean showing = mBatteryPercentView != null;
        boolean mShow = mForceShowPercent;

        if (mShowBatteryPercent == 1) {
                mShow = true;      
        } else if (mShowBatteryPercent == 2) {
                mShow = false;
        }

        if (mShow) {
            if (!showing) {
                mBatteryPercentView = loadPercentView();
                addView(mBatteryPercentView,
                        0,
                        new ViewGroup.LayoutParams(
                                LayoutParams.WRAP_CONTENT,
                                LayoutParams.MATCH_PARENT));
            }
            if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
            updatePercentText();
        } else {
            if (showing) {
                removeView(mBatteryPercentView);
                mBatteryPercentView = null;
            }
        }
        mDrawable.showPercentInsideCircle(!mCharging && !mShow && mShowBatteryPercent != 2);
        mDrawable.refresh();
    }

    private void loadImageView() {
        mBatteryIconView = new ImageView(mContext);
        mBatteryIconView.setImageDrawable(mDrawable);
        MarginLayoutParams mlp;
        if (isCircleBattery()) {
            mlp = new MarginLayoutParams(
                     getResources().getDimensionPixelSize(R.dimen.status_bar_battery_circle_icon_width),
                     getResources().getDimensionPixelSize(R.dimen.status_bar_battery_circle_icon_height));
        } else {
            mlp = new MarginLayoutParams(
                     getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                     getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
        }
        mlp.setMargins(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);
        scaleBatteryMeterViews();
    }

    private void reloadImage() {
        final boolean showing = mBatteryIconView != null;
        if (showing) {
            removeView(mBatteryIconView);
            mBatteryIconView = null;
        }
        updateShowImage();
    }

    private void updateShowImage() {
        final boolean showing = mBatteryIconView != null;
        if (mShowBatteryImage) {
            if (!showing) {
                loadImageView();
            }
        } else {
            if (showing) {
                removeView(mBatteryIconView);
                mBatteryIconView = null;
            }
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
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(
                isCircleBattery() ? R.dimen.status_bar_battery_circle_icon_height : R.dimen.status_bar_battery_icon_height);
        int batteryWidth = res.getDimensionPixelSize(
                isCircleBattery() ? R.dimen.status_bar_battery_circle_icon_width :R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));
        scaledLayoutParams.setMargins(0, 0, 0, marginBottom);

        if (mBatteryIconView != null) {
            mBatteryIconView.setLayoutParams(scaledLayoutParams);
        }
        if (mBatteryPercentView != null) {
            FontSizeUtils.updateFontSize(mBatteryPercentView, R.dimen.qs_time_expanded_size);
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mDarkIntensity = darkIntensity;
        float intensity = DarkIconDispatcher.isInArea(area, this) ? darkIntensity : 0;
        int foreground = getColorForDarkIntensity(intensity, mLightModeFillColor,
                mDarkModeFillColor);
        int background = getColorForDarkIntensity(intensity, mLightModeBackgroundColor,
                mDarkModeBackgroundColor);
        mDrawable.setColors(foreground, background);
        setTextColor(foreground);
    }

    public void setTextColor(int color) {
        mTextColor = color;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(color);
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
}
