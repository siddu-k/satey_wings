# Satey Wings

A comprehensive mobile and web application project designed to provide innovative solutions with modern technology stack. This project combines Android development, web technologies, and database infrastructure to create a seamless user experience.

## 📋 Project Overview

Satey Wings is a full-stack application featuring:
- **Android Mobile App** - Built with Kotlin for native Android development
- **Web Frontend** - Modern web interface using TypeScript and JavaScript
- **Backend Infrastructure** - Database management with PostgreSQL/PL-pgSQL
- **API Layer** - Serverless functions and notification system

## 🛠 Tech Stack

| Component | Technology | Percentage |
|-----------|-----------|-----------|
| **Core** | Kotlin | 82.1% |
| **Database** | PL-pgSQL (PostgreSQL) | 5.5% |
| **Web** | TypeScript | 4.7% |
| **Frontend** | JavaScript | 4.6% |
| **Styling** | CSS | 1.5% |
| **Markup** | HTML | 1.1% |
| **Automation** | Batch Scripts | 0.5% |

## 📁 Project Structure

```
satey_wings/
├── app/                           # Android Application
├── webmodel/                      # Web Frontend Models
├── vosk-wrapper/                  # Voice Recognition Integration
├── supabase/                      # Backend Database Configuration
├── schemas/                       # Database Schemas
├── docs/                          # Documentation
├── build.gradle.kts               # Gradle Build Configuration
├── settings.gradle.kts            # Gradle Settings
├── gradle.properties              # Gradle Properties
├── InstallAndRun.bat              # Installation & Run Script
├── QuickInstall.bat               # Quick Installation Script
├── vercel-sendNotification-FIXED.js  # Notification Service
├── supabase_schema                # Database Schema Definition
└── keystore.properties            # Signing Configuration
```

## 🚀 Getting Started

### Prerequisites
- Java Development Kit (JDK) 11 or higher
- Android Studio
- Node.js and npm (for web components)
- Git

### Installation

#### Quick Start (Windows)
```bash
# Run the quick installation script
QuickInstall.bat
```

#### Full Installation & Run (Windows)
```bash
# Run the full installation and launch script
InstallAndRun.bat
```

#### Manual Installation
```bash
# Clone the repository
git clone https://github.com/siddu-k/satey_wings.git
cd satey_wings

# Build the Android app
./gradlew build

# Build the web frontend
cd webmodel
npm install
npm run build
```

## 🔧 Build & Development

### Android App Development
```bash
# Build the Android application
./gradlew build

# Run on connected device/emulator
./gradlew installDebug
```

### Web Frontend Development
```bash
cd webmodel
npm install
npm start  # For development server
npm run build  # For production build
```

### Database Setup
- Database schemas are defined in the `schemas/` directory
- PostgreSQL configuration available in the `supabase/` directory
- Use `supabase_schema` file for schema migrations

## 🔐 Security

- Release signing configuration managed in `keystore.properties`
- Secure API key management through environment variables
- Android keystore included: `vasateysec-release.jks`

## 📢 Features

- **Voice Recognition Integration** - Vosk wrapper for speech-to-text capabilities
- **Notification System** - Serverless notification delivery via Vercel functions
- **Database Backend** - Supabase PostgreSQL integration
- **Multi-platform** - Native Android app with web interface

## 📚 Documentation

Detailed documentation is available in the `/docs` directory covering:
- Architecture overview
- API documentation
- Deployment guide
- Contribution guidelines

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📱 Platform Support

- **Android** - Primary mobile platform
- **Web** - TypeScript/JavaScript web application
- **Database** - PostgreSQL (via Supabase)

## 📞 Project Reference

For more details about this project, visit the creator's LinkedIn profile:
[K Sridatta - Satey Wings Treasury Project](https://www.linkedin.com/in/ksridatta/overlay/Project/268010527/treasury/?profileId=ACoAAE0Vj1wBU7XTIOR-iSf-SIF2RDrUgycpylQ)

## 📄 License

This project is proprietary. All rights reserved.

## 👨‍💻 Author

**K Sridatta**
- GitHub: [@siddu-k](https://github.com/siddu-k)
- LinkedIn: [K Sridatta](https://www.linkedin.com/in/ksridatta/)

---

**Last Updated:** June 2026

For issues, questions, or contributions, please open an issue on GitHub.
