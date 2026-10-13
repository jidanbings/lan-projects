package io.lanprojects.phone;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TableAwareMovementMethod;
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

    /**
     * Render markdown into the TextView, then swap in TableAwareMovementMethod
     * so links inside table cells are clickable too. Markwon draws tables as
     * custom spans where each cell has its own inner text layout, so the plain
     * LinkMovementMethod it installs cannot hit-test them (a touch maps to the
     * wrong character offset and no ClickableSpan is found); TableAwareMovementMethod
     * delegates to LinkMovementMethod and additionally maps touches into table
     * cells. Harmless on pages without tables.
     */
    static void render(AppCompatActivity activity, TextView textView, String markdown) {
        create(activity).setMarkdown(textView, markdown);
        MovementMethod current = textView.getMovementMethod();
        if (!(current instanceof TableAwareMovementMethod)) {
            MovementMethod base = current != null ? current : LinkMovementMethod.getInstance();
            textView.setMovementMethod(TableAwareMovementMethod.wrap(base));
        }
    }
}
