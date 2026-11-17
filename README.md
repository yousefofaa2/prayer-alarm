# منبه الصلاة (Prayer Alarm)

تطبيق أندرويد بيشتغل **بدون إنترنت** وفيه:
- مواقيت الصلاة (حساب محلي بطريقة الهيئة المصرية) مع الصلاة القادمة
- ضبط منبهات الصلوات في تطبيق الساعة الأصلي
- بوصلة اتجاه القبلة
- المصحف الشريف كامل للقراءة (114 سورة)

## البناء
الأفضل تبنيه عن طريق GitHub Actions (ملف .github/workflows/build.yml)، وهو بينزّل نص المصحف
ويتأكد منه (114 سورة / 6236 آية) ويحطه جوه التطبيق ويطلّع ملف APK في صفحة Releases.

لو هتبنيه محليًا في Android Studio، نزّل نص المصحف الأول:
```
mkdir -p app/src/main/assets
curl -L https://api.alquran.cloud/v1/quran/quran-uthmani -o app/src/main/assets/quran.json
```
