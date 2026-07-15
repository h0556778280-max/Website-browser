package com.example.ui

import android.net.Uri

object BrowserConfig {
    /**
     * כתובת האתר שתיפתח מיד עם פתיחת האפליקציה או בעת יצירת כרטיסייה חדשה.
     * ניתן להגדיר כ- "about:blank" כדי להציג את מסך הבית המשודרג (הדאשבורד).
     */
    const val INITIAL_URL: String = "https://www.google.com"

    /**
     * רשימת הדומיינים המורשים לגלישה בדפדפן.
     * 
     * תמיכה חכמה בתתי-דומיינים:
     * - אם נרשם דומיין ראשי (למשל "google.com"), הדפדפן יאפשר גישה אליו ולכל תתי-הדומיינים שלו (כמו "mail.google.com").
     * - אם נרשם דומיין המכיל תת-דומיין ספציפי (למשל "mail.google.com"), הדפדפן יאפשר גישה אך ורק לתת-הדומיין הזה ותתי-הדומיינים שלו (כמו "sub.mail.google.com"),
     *   ויחסום גישה לדומיין הראשי ("google.com") או לתתי-דומיינים אחרים ("drive.google.com").
     */
    val ALLOWED_DOMAINS: List<String> = listOf(
        "google.com",
        "google.co.il",
        "wikipedia.org",
        "github.com",
        "w3schools.com"
    )

    /**
     * רשימת תתי-דומיינים מוחרגים (חסומים).
     * מאפשרת להחריג ולחסום תת-דומיין מסוים, גם אם הדומיין הראשי שלו מופיע ברשימת המורשים (ALLOWED_DOMAINS).
     * 
     * דוגמה:
     * אם "google.com" מורשה, אך נוסיף כאן "ads.google.com", אז "ads.google.com" ותתי-הדומיינים שלו יחסמו לחלוטין.
     */
    val EXCLUDED_DOMAINS: List<String> = listOf(
        "ads.google.com",
        "unwanted-sub.wikipedia.org" // דוגמאות להחרגה
    )

    /**
     * פונקציית עזר לבדיקה האם URL מסוים מורשה לגלישה על פי הגדרות הדומיינים וההחרגות.
     */
    fun isUrlAllowed(url: String): Boolean {
        if (url == "about:blank" || url.startsWith("about:") || url.startsWith("data:")) {
            return true
        }
        
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            null
        } ?: return false
        
        val host = uri.host?.lowercase() ?: return false
        
        // 1. בדיקת החרגות (Excluded Domains) - אם הכתובת מוחרגת, נחסום מיד
        for (excluded in EXCLUDED_DOMAINS) {
            val lowerExcluded = excluded.lowercase().trim()
            if (host == lowerExcluded || host.endsWith(".$lowerExcluded")) {
                return false // חסום לחלוטין עקב החרגה
            }
        }
        
        // אם רשימת המורשים ריקה, מאפשרים הכל (מלבד המוחרגים)
        if (ALLOWED_DOMAINS.isEmpty()) {
            return true
        }
        
        // 2. בדיקת מורשים (Allowed Domains)
        for (domain in ALLOWED_DOMAINS) {
            val lowerDomain = domain.lowercase().trim()
            if (host == lowerDomain || host.endsWith(".$lowerDomain")) {
                return true // מורשה
            }
        }
        
        return false
    }
}
