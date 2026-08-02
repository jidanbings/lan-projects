package io.lanprojects.phone;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AppCompatActivity;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * Shared Markdown setup used by every screen that renders markdown in the app
 * (About, Privacy, update release notes): GFM tables + strikethrough are
 * enabled and links open in an external browser. Keeping it in one place so
 * the update dialog renders release notes exactly like the other pages.
 */
final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    static Markwon create(AppCompatActivity activity) {
        return Markwon.builder(activity)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(activity))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                        builder.linkResolver((view, link) -> {
                            if (link != null
                                    && (link.startsWith("http://") || link.startsWith("https://"))) {
                                try {
                                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                                } catch (ActivityNotFoundException ignored) {
                                }
                            }
                        });
                    }
                })
                .build();
    }
}
