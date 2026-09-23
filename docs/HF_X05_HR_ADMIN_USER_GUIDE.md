# HF-X05 Attendance System
# HR Administrator User Guide

This guide explains how HR and hospital administrators use the HF-X05 Attendance System to manage employee face and fingerprint registration, device options, backup, and daily attendance.

## Before You Start

- Keep the terminal powered and connected to the network where possible.
- Use an active administrator account to open the Admin area.
- Ensure the employee record has been synchronized to the device before registering a biometric method.
- Keep the camera area and fingerprint sensor clean and unobstructed.

## Normal Attendance Flow

The Home screen is a launcher for the recognition methods enabled on this device. It does not scan fingerprints continuously.

When both methods are enabled, Home shows **READY** and **Choose a recognition method**.

```text
HOME
 |
 +-- Face Recognition
 |      |
 |      +--> Face identified
 |              |
 |              +--> CHECK IN or CHECK OUT
 |                      |
 |                      +--> Attendance result --> Home
 |
 +-- Fingerprint Recognition
        |
        +--> PLACE YOUR FINGER
                |
                +--> Identify employee
                        |
                        +--> Attendance recorded --> Home
```

Depending on **Device Settings**, Home can show **Face Recognition**, **Fingerprint Recognition**, or both.

## Mark Attendance with Face Recognition

1. On Home, tap **Face Recognition**.
2. Follow the on-screen instruction to position the employee’s face in the frame.
3. Wait for the employee to be identified.
4. Tap **CHECK IN** or **CHECK OUT**.
5. Read the result message. A successful result shows the employee and attendance time, then the app returns to Home.

If the face cannot be identified, use **TRY AGAIN** or return to Home.

## Mark Attendance with Fingerprint Recognition

1. On Home, tap **Fingerprint Recognition**.
2. The dedicated Fingerprint Recognition screen displays **PLACE YOUR FINGER**.
3. Place an enrolled finger naturally on the sensor and keep it still until the app finishes identifying the employee.
4. Wait while the app records attendance.
5. Read the result. The app returns to Home automatically after the result.

Use **Cancel** to leave the scan screen. If the fingerprint is not recognized, tap **Try Again** and place the enrolled finger again.

## Enter the Admin Area

1. On Home, tap the Admin icon.
2. If requested, enter the administrator email/username and password.
3. Tap **LOGIN**.
4. After successful sign-in, the **Admin Dashboard** opens.

The Admin Dashboard contains:

- **User Management**
- **Backup & Restore**
- **Device Settings**
- **Logout**

Select **Logout** when administration is complete, especially on a shared terminal.

## User Management

Use User Management to find an employee and manage that employee’s biometric registrations.

1. Open **Admin** > **User Management**.
2. Use **Search users** to enter a name, employee ID, or user ID.
3. If the required employee is not listed, tap the refresh button to refresh users when a network connection is available.
4. Tap the employee’s card to open their biometric management screen.

Each employee card shows their active/inactive status and a summary of:

- Face: **Registered** or **Not registered**
- Fingerprint: registered fingers, **Not registered**, or **Sync required**

The employee biometric screen is the central place for face and fingerprint management.

```text
HOME
 |
 +--> Admin
        |
        +--> User Management
        |      |
        |      +--> Search Employee
        |              |
        |              +--> Employee Biometric Management
        |                       |
        |                       +--> Face Recognition
        |                       |      +--> Register / View photos / Delete
        |                       |
        |                       +--> Fingerprint
        |                              +--> Register Fingerprint
        |                              +--> Manage Fingerprints
        |                                     +--> Delete / re-register
        |
        +--> Device Settings
        |
        +--> Backup & Restore
        |
        +--> Logout
```

## Register an Employee Fingerprint

Use this route: **Admin** > **User Management** > employee > **REGISTER FINGERPRINT**.

1. Open the employee’s biometric management screen.
2. Under **Fingerprint**, tap **REGISTER FINGERPRINT**. This is available for active employees whose fingerprint is not already registered.
3. On the Fingerprint Registration screen, choose the finger from the **Finger** list.
4. Tap **START ENROLLMENT**.
5. Ask the employee to place the selected finger naturally on the sensor.
6. Tap **CAPTURE NEXT** as instructed. The employee must lift and place the same finger again, with a slightly different position, for each capture.
7. Continue until all five captures are complete.
8. Wait for **Fingerprint Registered**. The registration is saved on the device; the app then attempts to synchronize it.
9. Tap **DONE** when shown.

If the employee already has a fingerprint registration, use **MANAGE FINGERPRINTS** to delete the existing enrolled finger before registering it again. Do not start a second registration for the same employee while an existing registration is still present.

```text
ADMIN > USER MANAGEMENT > EMPLOYEE
 |
 +--> REGISTER FINGERPRINT
        |
        +--> Choose Finger
                |
                +--> START ENROLLMENT
                        |
                        +--> CAPTURE NEXT x 5
                                |
                                +--> Fingerprint Registered
                                        |
                                        +--> DONE
```

## Manage, Delete, and Re-register a Fingerprint

Use this route: **Admin** > **User Management** > employee > **MANAGE FINGERPRINTS**.

The Fingerprint Management screen lists the employee’s saved fingerprint enrollment details, including the enrolled finger. It also provides **SYNC NOW** if fingerprint synchronization is needed.

To remove an enrolled fingerprint:

1. Open **MANAGE FINGERPRINTS** for the employee.
2. Find the enrolled finger in the list.
3. Tap **DELETE FINGERPRINT**.
4. Confirm with **DELETE**.
5. Wait for **Fingerprint deleted**.
6. Return to the employee biometric screen and use **REGISTER FINGERPRINT** to enroll the replacement finger.

Important: deleting a fingerprint removes that enrollment from the device and its synchronized backup. Confirm the employee and finger before approving deletion.

## Register or Manage Face Recognition

Use this route: **Admin** > **User Management** > employee.

Under **Face Recognition**:

- Tap **REGISTER FACE** for an active employee whose face is not registered.
- Follow the on-screen capture and review instructions, then save the completed registration.
- The employee screen changes to **Registered** after a successful registration.
- If available, **VIEW FACE PHOTOS** lets the administrator review registration photos.
- Tap **DELETE FACE** and confirm only when the employee’s face registration should be removed. Re-register the employee afterward if they still need Face Recognition.

## Device Settings

Open **Admin** > **Device Settings**.

### Attendance Methods

Under **Attendance Methods**, administrators can enable or disable:

- **Face Recognition**
- **Fingerprint Recognition**

The methods can be enabled independently. At least one must remain enabled; the app prevents an administrator from turning off the last available method.

An enabled method appears as a recognition option on Home. Enabling **Fingerprint Recognition** only makes its Home button available; it does not start continuous background scanning.

### Other Settings

- **Device ID**: update this only when instructed by the system administrator, then tap **SAVE DEVICE ID**.
- **Sound Feedback**: turn this on or off to control app sounds for recognition and attendance results.

### Application Update

Application Update is on **Admin** > **Device Settings**.

1. Confirm the device has an internet connection.
2. Under **Application Update**, tap **CHECK FOR UPDATE**.
3. If an update is available, review the displayed version and release notes.
4. Tap **DOWNLOAD UPDATE** and wait for the download and verification to complete.
5. Tap **INSTALL UPDATE**.
6. If Android asks for permission to install updates from this app, allow the permission and return to complete installation.

If the check, download, or verification fails, confirm the network connection and try again later. Do not install an update that the app has not verified.

## Backup & Restore

Open **Admin** > **Backup & Restore**. The screen shows the available fingerprint and face backup counts and the last successful backup time.

### Create a Backup

1. Confirm the device is connected to the network.
2. Tap **BACK UP NOW**.
3. Wait for **Backup Complete**.
4. If **Backup incomplete** appears, restore network access and try again.

Use a backup after significant biometric enrollment changes and before planned device replacement, reset, or service work.

### Restore Device Data

1. Confirm that the correct recovery backup is available for this device.
2. Tap **RESTORE DEVICE DATA**.
3. Read the warning carefully: restoration may replace current local data.
4. Tap **RESTORE** to confirm.
5. Wait for **Restore Complete**. If **Restore incomplete** appears, try again after checking the network.

> Warning: Restore should only be performed when required and after confirming the correct backup.

The screen also includes **DELETE CLOUD BACKUP**. Use it only when the online recovery copy must be removed. This does not delete local registrations already stored on the terminal.

## Offline Attendance

If the device temporarily loses internet access, attendance can be saved on the device and synchronized automatically when the network becomes available again. The event keeps the attendance time recorded by the device.

For face attendance, the app displays **Check In Saved** or **Check Out Saved** when it has saved attendance without a network connection. For fingerprint attendance, a pending result similarly confirms that attendance was saved for synchronization.

## Common Messages and Troubleshooting

| Message / Situation | What It Means | What HR Should Do |
|---|---|---|
| **No fingerprints enrolled** | No local fingerprint enrollment is available for fingerprint attendance. | Register an employee fingerprint through **User Management**. |
| **Fingerprint not registered. Please try again.** | The placed finger did not match a saved fingerprint. | Tap **Try Again** and use the enrolled finger. If the problem continues, confirm the employee’s registration. |
| **Fingerprint scanner unavailable** | The terminal cannot use the fingerprint scanner at this time. | Check that the device is powered and contact technical support if the message remains. |
| **Fingerprint Recognition is disabled** | Fingerprint attendance has been switched off in Device Settings. | Enable **Fingerprint Recognition** in **Admin** > **Device Settings**. |
| **Face Recognition is disabled** | Face attendance has been switched off in Device Settings. | Enable **Face Recognition** in **Admin** > **Device Settings**. |
| **Attendance already recorded** | The app has prevented another attendance event too soon after the last one. | Ask the employee to wait and try again later. |
| **Cannot Check Out** | No active Check In was found for the employee. | Confirm the employee has checked in, then contact the system administrator if a correction is needed. |
| **Employee record not found** | A biometric matched but the employee record is not available on the device. | Refresh users in **User Management** when connected to the network. |
| **Sync required** | Fingerprint information may need to be synchronized before it can be managed locally. | Open **MANAGE FINGERPRINTS** and use **SYNC NOW**; check the network connection. |
| No network connection / attendance saved | The device is temporarily offline. | Keep the device connected when possible. Attendance will synchronize automatically. |
| Update check or download failed | The app could not reach or download the update. | Confirm internet access and try again later. |

## Daily / Basic Administrator Checks

- Confirm the terminal is powered and the correct recognition buttons appear on Home.
- Confirm network access is available when user refresh, backup, restore, synchronization, or updates are needed.
- Keep the camera area and fingerprint sensor physically clean and unobstructed.
- If a user reports a problem, check the employee’s status in **User Management** and perform a test attendance attempt with the appropriate enrolled method.

## Quick Reference

**Mark attendance**

- Face: Home > **Face Recognition** > identify employee > **CHECK IN** or **CHECK OUT**
- Fingerprint: Home > **Fingerprint Recognition** > place enrolled finger > read result

**Register employee fingerprint**

Admin > **User Management** > employee > **REGISTER FINGERPRINT**

**Manage fingerprint**

Admin > **User Management** > employee > **MANAGE FINGERPRINTS**

**Register or manage face**

Admin > **User Management** > employee > **REGISTER FACE** / **DELETE FACE**

**Change recognition methods**

Admin > **Device Settings** > **Face Recognition** / **Fingerprint Recognition**

**Back up or restore**

Admin > **Backup & Restore** > **BACK UP NOW** / **RESTORE DEVICE DATA**

**Update application**

Admin > **Device Settings** > **Application Update** > **CHECK FOR UPDATE**

If an issue cannot be resolved using this guide, contact the system administrator or technical support.
