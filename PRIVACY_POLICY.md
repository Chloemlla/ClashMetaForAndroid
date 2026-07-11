# Privacy Policy

ClashMetaForAndroid is open-source software that runs primarily on your device. The project does not operate an account system or a server that receives your proxy profiles.

## Data handled by the app

To provide VPN and profile-management features, the app may store the following data locally:

- profile names, profile types, subscription source URLs, update intervals, and traffic metadata;
- imported and pending configuration files, override settings, selected proxies, and optional `ageSecretKey` values used to decrypt profiles;
- VPN, access-control, and user-interface preferences;
- the installed-app list needed to configure per-app VPN access control; and
- diagnostic logs when the user explicitly enables log recording.

Subscription URLs, provider URLs, and configuration-defined endpoints are contacted only to perform the actions requested by the user. Their operators receive the network information normally exposed by such a request, including the source IP address.

## System backup and device transfer

Android backup is limited to non-sensitive app preferences. The app explicitly excludes the profile database, imported and pending profile files, and Clash configuration/override files from cloud backup and device-to-device transfer. These exclusions cover subscription sources and `ageSecretKey` values stored in the profile database.

Older versions of the app used broader backup rules. Updating the app cannot delete copies that an Android backup provider may already hold; users should manage or remove existing device backups through their Android or cloud-account settings when needed.

## Logs and sharing

Diagnostic logs remain on the device unless the user chooses to export or share them. Logs may contain connection details or configuration-derived values, so users should review them before sharing. The project does not automatically upload app logs to an operator-controlled service.

## Platform and third-party processing

Android, the device vendor, the configured VPN/proxy endpoints, subscription providers, and any app store used to obtain the app may process data under their own policies. ClashMetaForAndroid does not control those services.

## Security

The project uses reasonable safeguards, but no software, network transmission, or local storage method can be guaranteed completely secure. Users should protect subscription credentials, exported profiles, logs, and decryption keys as sensitive information.

## Children

The app is not directed to children under 13 and does not knowingly operate a service that collects personal information from children.

## Changes and contact

This policy may be updated with the source code. Questions or security reports can be submitted through the project's GitHub repository.
