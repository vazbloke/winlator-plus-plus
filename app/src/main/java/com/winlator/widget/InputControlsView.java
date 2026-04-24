package com.winlator.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.math.Mathf;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorFilter lightColorFilter;
    private ColorFilter darkColorFilter;
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private boolean moveElement = false;
    private int snappingSize;
    private float startX;
    private float startY;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[18];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private boolean showTouchscreenControls = true;

    private boolean isMouseDampenerHeld = false;
    
    // ADD THESE TWO LINES: Track the individual physical hardware inputs
    private final java.util.concurrent.ConcurrentHashMap<Integer, Float> mouseXSources = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Float> mouseYSources = new java.util.concurrent.ConcurrentHashMap<>();

    // Tracks if an axis is resting (0), pushed negative (-1), or positive (1)
    private final byte[] activeAxisStates = new byte[64];

    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x00000000);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            List<ControlElement> elements = profile.getElements();
            if (touchpadView != null && elements.isEmpty()) touchpadView.setPointerButtonRightEnabled(true);
            if (showTouchscreenControls) for (ControlElement element : elements) element.draw(canvas);
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xff000000);
        canvas.drawColor(Color.BLACK);

        paint.setAntiAlias(false);
        paint.setColor(0xff303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0xff424242);

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // If the user minimizes the app or opens a full-screen menu, stop the inputs!
        if (visibility != VISIBLE) {
            stopMouseMoveTimer();
        }
    }

    public synchronized void setProfile(ControlsProfile profile) {
        // If the profile is changing (or being cleared), kill the timer first
        if (this.profile != profile) {
            stopMouseMoveTimer();
        }
        
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        } else {
            this.profile = null;
        }
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public ColorFilter getLightColorFilter() {
        if (lightColorFilter == null) lightColorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
        return lightColorFilter;
    }

    public ColorFilter getDarkColorFilter() {
        if (darkColorFilter == null) darkColorFilter = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
        return darkColorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            
            final float baseMultiplier = 15f * profile.getCursorSpeed();
            
            // --- OLD STYLE CONSTANTS (Blended Curve + Ramp Acceleration) ---
            final float linearWeight = 0.4f; 
            final float expWeight = 0.6f;
            final float accelThresholdSq = 0.95f * 0.95f; 
            final float maxAccel = 2.5f; 
            final float accelRate = 0.05f;

            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                
                // Shared Accumulator
                private float accumX = 0f;
                private float accumY = 0f;

                // Old Style State Variables
                private float currentAccel = 1.0f;

                // New Style State Variables (Air Hockey Drift)
                private float currentSpeed = 0f;
                private float currentAngle = 0f;
                private float angularVelocity = 0f;

                @Override
                public void run() {
                    // THE RESUME SHIELD: Protect background injections
                    if (!readyToDraw) return;

                    float x = mouseMoveOffset.x;
                    float y = mouseMoveOffset.y;

                    // --- THE DPI CLUTCH ---
                    // Create a local multiplier so we don't permanently alter the base.
                    float activeMultiplier = baseMultiplier;

                    if (isMouseDampenerHeld) {
                        // Crush the speed down to 30% for ultra-fine aiming
                        activeMultiplier *= 0.3f;
                    }
                    
                    float exactDx = 0f;
                    float exactDy = 0f;

                    // =======================================================
                    // Blended Curve with Time-Based Ramp-Up
                    // =======================================================
                    float magnitudeSq = (x * x) + (y * y);

                    if (magnitudeSq > accelThresholdSq) {
                        currentAccel = Math.min(maxAccel, currentAccel + accelRate);
                    } else {
                        currentAccel = 1.0f;
                    }

                    float curveX = x * (linearWeight + (expWeight * Math.abs(x)));
                    float curveY = y * (linearWeight + (expWeight * Math.abs(y)));

                    exactDx = curveX * activeMultiplier * currentAccel;
                    exactDy = curveY * activeMultiplier * currentAccel;

                    // --- SHARED ACCUMULATOR & INJECTION ---
                    accumX += exactDx;
                    accumY += exactDy;

                    int dx = (int) accumX;
                    int dy = (int) accumY;

                    if (dx != 0 || dy != 0) {
                        
                        // --- THE CORNER CRASH FIX ---
                        // Check local bounds before injecting so we don't push the Android 
                        // pointer into negative memory while waiting for Windows to correct it.
                        if (xServer.pointer != null && xServer.screenInfo != null) {
                            int currentX = xServer.pointer.getX();
                            int currentY = xServer.pointer.getY();
                            
                            // Trim DX so it cannot push X below 0 or above (width - 1)
                            int minDx = -currentX;
                            int maxDx = (xServer.screenInfo.width - 1) - currentX;
                            dx = Math.max(minDx, Math.min(dx, maxDx));
                            
                            // Trim DY so it cannot push Y below 0 or above (height - 1)
                            int minDy = -currentY;
                            int maxDy = (xServer.screenInfo.height - 1) - currentY;
                            dy = Math.max(minDy, Math.min(dy, maxDy));
                        }

                        // Only inject if there is still delta left after trimming
                        if (dx != 0 || dy != 0) {
                            accumX -= dx;
                            accumY -= dy;
                            xServer.injectPointerMoveDelta(dx, dy);
                        } else {
                            // We hit the wall! Bleed off the accumulator so 
                            // it doesn't build up massive "off-screen" momentum.
                            accumX = 0f;
                            accumY = 0f;
                        }
                    }
                }
            }, 0, 1000 / 60);
        }
    }

    private void stopMouseMoveTimer() {
        // 1. Kill the runaway timer
        if (mouseMoveTimer != null) {
            mouseMoveTimer.cancel();
            mouseMoveTimer.purge();
            mouseMoveTimer = null;
        }
        
        // 2. Wipe the analog input dictionaries
        mouseXSources.clear();
        mouseYSources.clear();
        mouseMoveOffset.set(0, 0);

        // 3. Wipe the digital keyboard states (prevents stuck keys on resume)
        if (activeAxisStates != null) {
            for (int i = 0; i < activeAxisStates.length; i++) {
                activeAxisStates[i] = 0;
            }
        }
    }

    private void processJoystickInput(ExternalController controller) {
        // A solid deadzone prevents stick drift from accidentally typing keyboard keys
        float DIGITAL_DEADZONE = 0.3f; 
        
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};

        for (byte i = 0; i < axes.length; i++) {
            int axis = axes[i];
            float value = values[i];
            byte currentSign = Mathf.sign(value);
            
            // Look up what this direction is mapped to
            int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, currentSign);
            ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
            
            // Check if this specific mapping is an analog mouse movement
            boolean isMouseMovement = controllerBinding != null && 
                (controllerBinding.getBinding() == Binding.MOUSE_MOVE_LEFT || 
                 controllerBinding.getBinding() == Binding.MOUSE_MOVE_RIGHT || 
                 controllerBinding.getBinding() == Binding.MOUSE_MOVE_UP || 
                 controllerBinding.getBinding() == Binding.MOUSE_MOVE_DOWN);

            // If it's a keyboard key, enforce the deadzone. If it's a mouse, allow micro-movements.
            byte effectiveSign = 0;
            if (isMouseMovement) {
                effectiveSign = (Math.abs(value) > 0.0f) ? currentSign : 0;
            } else {
                effectiveSign = (Math.abs(value) > DIGITAL_DEADZONE) ? currentSign : 0;
            }

            byte previousSign = activeAxisStates[axis];

            // 1. STATE CHANGE: The stick was pushed past the deadzone, or returned to center
            if (effectiveSign != previousSign) {
                
                // If it was previously pushed, SEND A KEY RELEASE for the old direction
                if (previousSign != 0) {
                    int keyCodeRel = ExternalControllerBinding.getKeyCodeForAxis(axis, previousSign);
                    ExternalControllerBinding bindRel = controller.getControllerBinding(keyCodeRel);
                    if (bindRel != null) handleInputEvent(bindRel.getBinding(), false, value, keyCodeRel);
                }

                // If it is newly pushed, SEND A KEY PRESS for the new direction
                if (effectiveSign != 0) {
                    int keyCodePress = ExternalControllerBinding.getKeyCodeForAxis(axis, effectiveSign);
                    ExternalControllerBinding bindPress = controller.getControllerBinding(keyCodePress);
                    if (bindPress != null) handleInputEvent(bindPress.getBinding(), true, value, keyCodePress);
                }

                // Save the new state
                activeAxisStates[axis] = effectiveSign;
            } 
            
            // 2. CONTINUOUS HOLD: The stick hasn't changed state.
            else if (effectiveSign != 0) {
                // Keyboards should do NOTHING here to prevent input flooding.
                // But Mouse movements MUST continuously send their updated float values for aiming!
                if (isMouseMovement) {
                    handleInputEvent(controllerBinding.getBinding(), true, value, keyCode);
                }
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // THE RESUME SHIELD
        if (!readyToDraw) return true;

        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                GamepadState state = controller.getGamepadState();
                ExternalControllerBinding controllerBinding;
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), state.isPressed(ExternalController.IDX_BUTTON_L2), 0, KeyEvent.KEYCODE_BUTTON_L2);

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), state.isPressed(ExternalController.IDX_BUTTON_R2), 0, KeyEvent.KEYCODE_BUTTON_R2);
                
                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // THE RESUME SHIELD: Consume the touch but do nothing
        if (!readyToDraw) return true;

        if (editMode) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    startX = event.getX();
                    startY = event.getY();

                    ControlElement element = intersectElement(startX, startY);
                    moveCursor = true;
                    moveElement = false;
                    if (element != null) {
                        offsetX = startX - element.getX();
                        offsetY = startY - element.getY();
                        moveCursor = false;
                    }

                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);

                        if (dx >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE || dy >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE) moveElement = true;

                        if (moveElement) {
                            selectedElement.setX((int)Mathf.roundTo(event.getX() - offsetX, snappingSize));
                            selectedElement.setY((int)Mathf.roundTo(event.getY() - offsetY, snappingSize));
                            invalidate();
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    if (selectedElement != null && profile != null && moveElement) profile.save();
                    if (moveCursor) cursor.set((int)Mathf.roundTo(event.getX(), snappingSize), (int)Mathf.roundTo(event.getY(), snappingSize));
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) handled = true;
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(i, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId, x, y)) handled = true;
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
            }
        }
        return true;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            
            // 1. Check if the device sending the key is our active Gamepad
            ExternalController controller = profile.getController(event.getDeviceId());
            
            if (controller != null) {
                int code = event.getKeyCode();


                // Let shared Odin system keys pass
                if (code == KeyEvent.KEYCODE_BACK ||
                    code == KeyEvent.KEYCODE_VOLUME_UP ||
                    code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return false; 
                }

                // THE RESUME SHIELD: Ignore inputs if the screen is still transitioning
                if (!readyToDraw) return true;

                // Process the mapped Gamepad button
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(code);
                if (controllerBinding != null) {
                    int action = event.getAction();

                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controllerBinding.getBinding(), false);
                    }
                    
                    // Swallow the mapped gamepad key
                    return true;
                }
                
                // If it's an unmapped gamepad key, STILL swallow it so Android doesn't 
                // synthesize ghost "Enter" or "Spacebar" presses in the background.
                return true;
            }
        }

        // 2. THE BLACKLIST FALLBACK 
        // We return false. The input goes straight to Winlator's XServer keyboard handler!
        return false;
    }

    // Keep backward compatibility for touch controls
    public void handleInputEvent(Binding[] bindings, boolean isActionDown) {
        for (Binding binding : bindings) {
            if (binding != Binding.NONE) handleInputEvent(binding, isActionDown, 0, 0);
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(binding, isActionDown, 0, 0);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(binding, isActionDown, offset, 0);
    }

    // THE NEW MASTER HANDLER
    public void handleInputEvent(Binding binding, boolean isActionDown, float offset, int sourceId) {
        if (binding.isGamepad()) {
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= 11) {
                state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null) winHandler.gamepadHandler.sendGamepadState(profile);
        }
        else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                if (isActionDown) {
                    // Extract true magnitude and apply binding direction
                    float magnitude = offset != 0 ? Math.abs(offset) : 1f;
                    mouseXSources.put(sourceId, binding == Binding.MOUSE_MOVE_LEFT ? -magnitude : magnitude);
                } else {
                    mouseXSources.remove(sourceId); // Remove only THIS specific physical button/stick
                }
                
                // Sum all active inputs (allows both joysticks to work together)
                float totalX = 0f;
                for (float v : mouseXSources.values()) totalX += v;
                mouseMoveOffset.x = Math.max(-1f, Math.min(1f, totalX)); // Clamp -1 to 1
                
                if (isActionDown || totalX != 0) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                if (isActionDown) {
                    float magnitude = offset != 0 ? Math.abs(offset) : 1f;
                    mouseYSources.put(sourceId, binding == Binding.MOUSE_MOVE_UP ? -magnitude : magnitude);
                } else {
                    mouseYSources.remove(sourceId);
                }
                
                float totalY = 0f;
                for (float v : mouseYSources.values()) totalY += v;
                mouseMoveOffset.y = Math.max(-1f, Math.min(1f, totalY)); 
                
                if (isActionDown || totalY != 0) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MIDDLE_BUTTON) {
                isMouseDampenerHeld = isActionDown;
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                icons[id] = BitmapFactory.decodeStream(is);
            }
            catch (IOException e) {}
        }
        return icons[id];
    }
}
