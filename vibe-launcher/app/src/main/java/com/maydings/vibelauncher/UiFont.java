package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * A font named by one of the font_* strings, for text built in code: Outfit (the string points at a font
 * resource), or a system family by name where a language needs it (values-vi/fonts.xml).
 */
final class UiFont {

    private UiFont() {
    }

    static Typeface get(Context context, int stringRes) {
        TypedValue v = new TypedValue();
        context.getResources().getValue(stringRes, v, true);
        if (v.type == TypedValue.TYPE_STRING && v.string != null && !v.string.toString().startsWith("res/")) {
            return Typeface.create(v.string.toString(), Typeface.NORMAL);
        }
        return context.getResources().getFont(v.resourceId);
    }
}
