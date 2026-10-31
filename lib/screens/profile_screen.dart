import 'dart:io';

import 'package:flutter/material.dart';

/// Profile presentation only. Account settings and persistence remain owned by
/// the application shell so transport/service side effects stay centralized.
class ProfileScreen extends StatelessWidget {
  final String userName;
  final String profilePhotoPath;
  final bool nearbyVisible;
  final VoidCallback onOpenSettings;

  const ProfileScreen({
    super.key,
    required this.userName,
    required this.profilePhotoPath,
    required this.nearbyVisible,
    required this.onOpenSettings,
  });

  @override
  Widget build(BuildContext context) {
    final photo = profilePhotoPath;
    final hasPhoto = photo.isNotEmpty && File(photo).existsSync();

    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const SizedBox(height: 24),
        Center(
          child: CircleAvatar(
            radius: 48,
            backgroundImage: hasPhoto ? FileImage(File(photo)) : null,
            child: hasPhoto ? null : const Icon(Icons.person, size: 48),
          ),
        ),
        const SizedBox(height: 12),
        Center(
          child: Text(
            userName,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
          ),
        ),
        const SizedBox(height: 24),
        Card(
          child: ListTile(
            leading: Icon(
              nearbyVisible ? Icons.visibility_outlined : Icons.visibility_off_outlined,
            ),
            title: const Text('الظهور للمستخدمين القريبين'),
            subtitle: Text(
              nearbyVisible
                  ? 'الظهور مفعّل وفق إعدادات بلوساب'
                  : 'الظهور متوقف وفق إعدادات بلوساب',
            ),
            trailing: Text(
              nearbyVisible ? 'مفعّل' : 'متوقف',
              style: TextStyle(
                color: nearbyVisible
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w600,
              ),
            ),
            onTap: onOpenSettings,
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.settings_outlined),
            title: const Text('الإعدادات'),
            subtitle: const Text('الحساب والمظهر والخصوصية والاتصال'),
            trailing: const Icon(Icons.chevron_left),
            onTap: onOpenSettings,
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.edit_outlined),
            title: const Text('تعديل الملف الشخصي'),
            subtitle: const Text('تغيير الاسم والصورة من الإعدادات'),
            trailing: const Icon(Icons.chevron_left),
            onTap: onOpenSettings,
          ),
        ),
      ],
    );
  }
}
