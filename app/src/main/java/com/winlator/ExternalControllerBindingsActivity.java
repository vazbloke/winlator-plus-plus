package com.winlator;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.core.AppUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;

import java.util.HashMap;
import java.util.Map;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private TextView emptyTextView;
    private ControlsProfile profile;
    private ExternalController controller;
    private RecyclerView recyclerView;
    private ControllerBindingsAdapter adapter;

    private int lastProcessedJoystickCode = KeyEvent.KEYCODE_UNKNOWN;

    private static final float JOYSTICK_VALUE_THRESHOLD = 0.3f;

    private static final Map<Integer, Binding> axisBindings = new HashMap<>();
    static {
        axisBindings.put((int)ExternalControllerBinding.AXIS_X_NEGATIVE, Binding.MOUSE_MOVE_LEFT);
        axisBindings.put((int)ExternalControllerBinding.AXIS_X_POSITIVE, Binding.MOUSE_MOVE_RIGHT);
        axisBindings.put((int)ExternalControllerBinding.AXIS_Y_NEGATIVE, Binding.MOUSE_MOVE_DOWN);
        axisBindings.put((int)ExternalControllerBinding.AXIS_Y_POSITIVE, Binding.MOUSE_MOVE_UP);
        axisBindings.put((int)ExternalControllerBinding.AXIS_Z_NEGATIVE, Binding.MOUSE_MOVE_LEFT);
        axisBindings.put((int)ExternalControllerBinding.AXIS_Z_POSITIVE, Binding.MOUSE_MOVE_RIGHT);
        axisBindings.put((int)ExternalControllerBinding.AXIS_RZ_NEGATIVE, Binding.MOUSE_MOVE_DOWN);
        axisBindings.put((int)ExternalControllerBinding.AXIS_RZ_POSITIVE, Binding.MOUSE_MOVE_UP);
        axisBindings.put(KeyEvent.KEYCODE_DPAD_LEFT, Binding.KEY_A);
        axisBindings.put(KeyEvent.KEYCODE_DPAD_RIGHT, Binding.KEY_D);
        axisBindings.put(KeyEvent.KEYCODE_DPAD_UP, Binding.KEY_W);
        axisBindings.put(KeyEvent.KEYCODE_DPAD_DOWN, Binding.KEY_S);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.external_controller_bindings_activity);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            profile.save();
        }

        Toolbar toolbar = findViewById(R.id.Toolbar);
        toolbar.setTitle(controller.getName());
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);

        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(this, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        recyclerView.setAdapter(adapter = new ControllerBindingsAdapter());
        updateEmptyTextView();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        int position;
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);

            controller.addControllerBinding(controllerBinding);
            profile.save();
            adapter.notifyDataSetChanged();
            updateEmptyTextView();
            position = controller.getPosition(controllerBinding);
        }
        else animateItemView(position = controller.getPosition(controllerBinding));
        // recyclerView.scrollToPosition(position);
    }

    private void processJoystickInput() {
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};

        int finalIndex = getIndexOfMaxAbsValue(values);
        float finalValue = values[finalIndex];

        if (Math.abs(finalValue) < JOYSTICK_VALUE_THRESHOLD) return;

        byte sign = Mathf.sign(finalValue);

        int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[finalIndex], sign);
        Binding binding = axisBindings.getOrDefault(keyCode, Binding.NONE);

        // THE DEBOUNCE: Only update the UI if it's a NEW directional movement.
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN && keyCode != lastProcessedJoystickCode) {
            updateControllerBinding(keyCode, binding);
            lastProcessedJoystickCode = keyCode; // Lock it so it doesn't fire again
        }
        // RESET THE LOCK when the driver reports the stick has returned to center (0).
        else if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            lastProcessedJoystickCode = KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    public static int getIndexOfMaxAbsValue(float[] array) {
        // Handle edge cases: null or empty array
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException("Array must not be null or empty");
        }

        int maxIndex = 0;
        float maxAbsValue = Math.abs(array[0]);

        // Iterate through the array starting from the second element
        for (int i = 1; i < array.length; i++) {
            float currentAbsValue = Math.abs(array[i]);

            // If the current absolute value is strictly greater, update our trackers
            if (currentAbsValue > maxAbsValue) {
                maxAbsValue = currentAbsValue;
                maxIndex = i;
            }
        }

        return maxIndex;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Joysticks only fire ACTION_MOVE. We need to catch the push (>0) AND the release (0)
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (event.getDeviceId() == controller.getDeviceId() && controller.updateStateFromMotionEvent(event)) {
                GamepadState state = controller.getGamepadState();

                if (state.isPressed(ExternalController.IDX_BUTTON_L2)) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
                if (state.isPressed(ExternalController.IDX_BUTTON_R2)) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);

                processJoystickInput();

                // ALWAYS return true here. This consumes BOTH the joystick being pushed
                // and the joystick returning to dead center, so the UI doesn't scroll.
                return true;
            }
        }

        // Catch-all: Swallow any other gamepad motions or synthesized ghost scrolls
        if (isGamepadEvent(event) || event.getAction() == MotionEvent.ACTION_SCROLL) {
            return true;
        }

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getDeviceId() == controller.getDeviceId()) {
            // ONLY map the key to Winlator on the initial press (ACTION_DOWN)
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                updateControllerBinding(event.getKeyCode(), Binding.NONE);
            }

            // CRITICAL: We return true for ALL actions (DOWN, UP, and MULTIPLE).
            // If we don't consume the ACTION_UP, the Android RecyclerView registers a click!
            return true;
        }

        // The Brick Wall: Allow ONLY system keys to function normally
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_VOLUME_UP ||
                code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        // Destroy every other ghost key (Enter, D-pad, Space) that the OS might synthesize
        return true;
    }

    // Add the helper method
    private boolean isGamepadEvent(InputEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
               (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        finish();
        return true;
    }

    private class ControllerBindingsAdapter extends RecyclerView.Adapter<ControllerBindingsAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton removeButton;
            private final TextView title;
            private final Spinner bindingType;
            private final Spinner binding;

            private ViewHolder(View view) {
                super(view);
                this.title = view.findViewById(R.id.TVTitle);
                this.bindingType = view.findViewById(R.id.SBindingType);
                this.binding = view.findViewById(R.id.SBinding);
                this.removeButton = view.findViewById(R.id.BTRemove);
            }
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.external_controller_binding_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ExternalControllerBinding item = controller.getControllerBindingAt(position);
            holder.title.setText(item.toString());
            loadBindingSpinner(holder, item);
            holder.removeButton.setOnClickListener((view) -> {
                controller.removeControllerBinding(item);
                profile.save();
                notifyDataSetChanged();
                updateEmptyTextView();
            });
        }

        @Override
        public final int getItemCount() {
            return controller.getControllerBindingCount();
        }

        private void loadBindingSpinner(ViewHolder holder, final ExternalControllerBinding item) {
            final Context $this = ExternalControllerBindingsActivity.this;

            Runnable update = () -> {
                String[] bindingEntries = null;
                switch (holder.bindingType.getSelectedItemPosition()) {
                    case 0:
                        bindingEntries = Binding.keyboardBindingLabels();
                        break;
                    case 1:
                        bindingEntries = Binding.mouseBindingLabels();
                        break;
                    case 2:
                        bindingEntries = Binding.gamepadBindingLabels();
                        break;
                }

                holder.binding.setAdapter(new ArrayAdapter<>($this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
                AppUtils.setSpinnerSelectionFromValue(holder.binding, item.getBinding().toString());
            };

            holder.bindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update.run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            Binding selectedBinding = item.getBinding();
            if (selectedBinding.isKeyboard()) {
                holder.bindingType.setSelection(0, false);
            }
            else if (selectedBinding.isMouse()) {
                holder.bindingType.setSelection(1, false);
            }
            else if (selectedBinding.isGamepad()) {
                holder.bindingType.setSelection(2, false);
            }

            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Binding binding = Binding.NONE;
                    switch (holder.bindingType.getSelectedItemPosition()) {
                        case 0:
                            binding = Binding.keyboardBindingValues()[position];
                            break;
                        case 1:
                            binding = Binding.mouseBindingValues()[position];
                            break;
                        case 2:
                            binding = Binding.gamepadBindingValues()[position];
                            break;
                    }

                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        profile.save();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            update.run();
        }
    }

    private void updateEmptyTextView() {
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void animateItemView(int position) {
        final ControllerBindingsAdapter.ViewHolder holder = (ControllerBindingsAdapter.ViewHolder)recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            final int color = AppUtils.getThemeColor(this, R.attr.colorAccent);
            final ValueAnimator animator = ValueAnimator.ofFloat(0.4f, 0.0f);
            animator.setDuration(200);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener((animation) -> {
                float alpha = (float)animation.getAnimatedValue();
                holder.itemView.setBackgroundColor(Color.argb((int)(alpha * 255), Color.red(color), Color.green(color), Color.blue(color)));
            });
            animator.start();
        }
    }
}
