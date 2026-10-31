# مراجعة أخطاء البناء الأربعة الظاهرة في صور Klencod IDE

تمت مراجعة ملفات المصدر الموجودة في هذه الحزمة بعد التعديلات.

## نتيجة المراجعة

1. `lib/main.dart:1037`
   - استدعاء `ScaffoldMessenger.of(context).showSnackBar(...)` مكتمل الأقواس والفواصل، ولا يظهر تركيب الشرط الثلاثي المكسور الموجود في سجل الخطأ السابق.

2. `lib/services/local_network_service.dart:256`
   - أصبح تدفق المقبس يحوّل النوع صراحةً باستخدام `socket.cast<List<int>>()` قبل `utf8.decoder`، بما يطابق مدخلات `Utf8Decoder`.

3. `lib/services/pairwise_chain_ratchet_service.dart:335`
   - متغير سلسلة الاستقبال معرف كـ `List<int>`، وهو متوافق مع القيمة التي يعيدها `_mac`.

4. `lib/services/pairwise_chain_ratchet_service.dart:501`
   - تم تطبيق تعريف النوع المتوافق نفسه في مسار معالجة الرسائل المتأخرة.

## حدود التحقق

- فُحصت سلامة أرشيف ZIP بنجاح.
- تمت مراجعة الأسطر المتأثرة مباشرةً.
- لم يتوفر Flutter/Dart SDK في بيئة العمل؛ لذلك لم يُنفّذ `flutter analyze` أو `flutter test` أو `flutter build apk`.
- لا تعتبر هذه المراجعة إثباتاً لنجاح بناء APK أو لاختبار الاتصال بين هاتفين.

## الخطوة التالية المقترحة

المرحلة 29 هي نقل إدارة مسار بيانات Wi-Fi Aware (NDP وNetworkCallback ومقابس TCP) من `MainActivity` إلى `BlousabBackgroundService`. ينبغي تنفيذها على مراحل صغيرة مع اختبار Gradle/Flutter بعد كل مرحلة، والإبقاء على شرط اختيار المستخدم قبل فتح الاتصال.
