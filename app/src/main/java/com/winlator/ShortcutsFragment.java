package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.CreateFolderDialog;
import com.winlator.contentdialog.ShortcutSettingsDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.ArrayUtils;
import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShortcutsFragment extends BaseFileManagerFragment<Shortcut> {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewStyle = ViewStyle.valueOf(preferences.getString("shortcuts_view_style", "GRID"));
    }

    @Override
    public void refreshContent() {
        super.refreshContent();

        Shortcut selectedFolder = !folderStack.isEmpty() ? folderStack.peek() : null;
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts(selectedFolder);
        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.shortcuts_menu, menu);
        refreshViewStyleMenuItem(menu.findItem(R.id.menu_item_view_style));
    }

    private void createFolder() {
        clearClipboard();
        if (manager.getContainers().isEmpty()) return;
        CreateFolderDialog createFolderDialog = new CreateFolderDialog(manager);
        createFolderDialog.setOnCreateFolderListener((container, name) -> {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            File parent = !folderStack.isEmpty() ? folderStack.peek().file : desktopDir;
            File file = new File(parent, name);
            if (file.isDirectory()) {
                AppUtils.showToast(getContext(), R.string.there_already_file_with_that_name);
            }
            else {
                file.mkdir();
                refreshContent();
            }
        });
        createFolderDialog.show();
    }

    @Override
    protected void pasteFiles() {
        if (folderStack.isEmpty()) {
            clearClipboard();
            AppUtils.showToast(getContext(), R.string.you_cannot_paste_files_here);
            return;
        }

        clipboard.targetDir = folderStack.peek().file;
        super.pasteFiles();
    }

    private void instantiateClipboard(Shortcut shortcut, boolean cutMode) {
        clearClipboard();
        File linkFile = shortcut.getLinkFile();
        File[] files = {new File(shortcut.file.getParentFile(), shortcut.file.getName())};
        if (shortcut.file.isFile()) files = ArrayUtils.concat(files, new File[]{new File(linkFile.getParentFile(), linkFile.getName())});

        clipboard = new Clipboard(files, cutMode);
        pasteButton.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_view_style) {
            setViewStyle(viewStyle == ViewStyle.GRID ? ViewStyle.LIST : ViewStyle.GRID);
            preferences.edit().putString("shortcuts_view_style", viewStyle.name()).apply();
            refreshViewStyleMenuItem(menuItem);
            return true;
        }
        else if (itemId == R.id.menu_item_new_folder) {
            createFolder();
            return true;
        }
        else if (itemId == R.id.menu_item_export_all) {
            exportAllShortcutsToFrontend();
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    private void exportAllShortcutsToFrontend() {
        Context context = getContext();
        ContentDialog.confirm(context, R.string.do_you_want_to_export_all_shortcuts_to_frontend, () -> {
            ArrayList<Shortcut> allShortcuts = manager.loadShortcuts(null);
            for (Shortcut shortcut : allShortcuts) {
                if (!shortcut.file.isDirectory()) exportShortcutToFrontend(shortcut, false);
            }
            AppUtils.showToast(context, R.string.shortcuts_exported_successfully);
        });
    }

    private void exportShortcutToFrontend(Shortcut shortcut) {
        exportShortcutToFrontend(shortcut, true);
    }

    private void exportShortcutToFrontend(Shortcut shortcut, boolean showToast) {
        Context context = getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String exportPath = preferences.getString("shortcut_export_path", AppUtils.DIRECTORY_DOWNLOADS + "/Winlator/FrontendShortcuts");
        File exportDir = new File(exportPath);

        if (!exportDir.exists() && !exportDir.mkdirs()) return;

        String fileName = shortcut.name + "-" + shortcut.container.id + ".desktop";
        File exportFile = new File(exportDir, fileName);
        List<String> lines = FileUtils.readLines(shortcut.file, true);

        if (FileUtils.writeLines(exportFile, lines)) {
            if (showToast) AppUtils.showToast(context, getString(R.string.shortcut_exported_to) + ": " + exportFile.getPath());
        }
    }

    private void runFromShortcut(Shortcut shortcut) {
        AppCompatActivity activity = (AppCompatActivity)getActivity();

        if (shortcut.file.isDirectory()) {
            folderStack.push(shortcut);
            refreshContent();

            ActionBar actionBar = activity.getSupportActionBar();
            if (actionBar != null) {
                actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
                actionBar.setTitle(shortcut.name);
            }
        }
        else {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            activity.startActivity(intent);
        }
    }

    @Override
    protected String getHomeTitle() {
        return getString(R.string.shortcuts);
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView imageView;
            private final TextView title;
            private final View menuButton;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int resource = viewStyle == ViewStyle.LIST ? R.layout.file_list_item : R.layout.file_grid_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(resource, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            if (item.file.isDirectory()) {
                holder.imageView.setImageResource(R.drawable.container_folder);
            }
            else {
                if (item.icon != null) {
                    holder.imageView.setImageBitmap(item.icon);
                }
                else holder.imageView.setImageResource(R.drawable.container_file_window);
            }
            holder.title.setText(item.name);
            holder.itemView.setOnClickListener((v) -> runFromShortcut(item));
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, Shortcut shortcut) {
            PopupMenu listItemMenu = new PopupMenu(getContext(), anchorView);
            listItemMenu.inflate(R.menu.file_manager_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            Menu menu = listItemMenu.getMenu();
            menu.findItem(R.id.menu_item_copy).setVisible(false);
            menu.findItem(R.id.menu_item_cut).setVisible(!shortcut.file.isDirectory());
            menu.findItem(R.id.menu_item_rename).setVisible(false);
            menu.findItem(R.id.menu_item_add_favorite).setVisible(false);
            menu.findItem(R.id.menu_item_info).setVisible(false);
            menu.findItem(R.id.menu_item_export_to_frontend).setVisible(!shortcut.file.isDirectory());

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.menu_item_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.menu_item_cut) {
                    instantiateClipboard(shortcut, true);
                }
                else if (itemId == R.id.menu_item_remove) {
                    ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_file, () -> {
                        shortcut.remove();
                        refreshContent();
                    });
                }
                else if (itemId == R.id.menu_item_export_to_frontend) {
                    exportShortcutToFrontend(shortcut);
                }
                return true;
            });
            listItemMenu.show();
        }
    }
}
