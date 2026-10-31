import 'package:flutter/material.dart';

import 'public_feed_screen.dart';
import 'stories_strip.dart';

/// Combined social landing page: stories stay visible above the public feed.
/// Existing story/feed widgets retain their own storage and synchronization logic.
class SocialHomeScreen extends StatelessWidget {
  final String userName;
  final String profilePhotoPath;

  const SocialHomeScreen({
    super.key,
    required this.userName,
    required this.profilePhotoPath,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Material(
          color: Theme.of(context).scaffoldBackgroundColor,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(16, 10, 16, 0),
                child: Text(
                  'القصص',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                ),
              ),
              StoriesStrip(
                userName: userName,
                profilePhotoPath: profilePhotoPath,
              ),
              Divider(
                height: 1,
                color: Theme.of(context).colorScheme.outlineVariant,
              ),
            ],
          ),
        ),
        Expanded(
          child: PublicFeedScreen(
            userName: userName,
            profilePhotoPath: profilePhotoPath,
          ),
        ),
      ],
    );
  }
}
