# Phase 15: Rollout Strategy

**Timeline:** 11-month progression from MVP to stable production

## Release Schedule

### Alpha (Months 1-3): Early Adopter Testing
**Target:** Developer-focused testing, gather feedback

**Version:** `0.1.0-alpha.1` through `0.1.0-alpha.4` (monthly releases)

**Features:**
- Phase 1-6: Core onboarding and Debian runtime
- Phase 7: Terminal with real PTY support
- Phase 8: Backend integration (outside Debian)

**Stability:** Pre-release, expect crashes and incomplete features

**Distribution:**
- GitHub Releases (dev builds only)
- Discord community (closed testing group)
- Direct APK distribution to trusted testers

**Success Metrics:**
- 100+ active alpha testers
- <2x crashes per session
- Onboarding successful on 90%+ devices
- Terminal executes basic commands

**Feedback Channels:** GitHub Issues, Discord #alpha-testing, weekly feedback survey

---

### Beta (Months 4-7): Public Testing
**Target:** Broader compatibility testing, refinement

**Version:** `0.2.0-beta.1` through `0.2.0-beta.6` (bi-weekly releases)

**New Features:**
- Phase 9: Package management UI (git, python, node install buttons)
- Phase 10: Project import/export, migration tools
- Phase 11: Health checks and auto-repair
- Phase 12: Security hardening (password policies, GPG verification)

**Stability:** Much more stable, core features fully functional

**Distribution:**
- Google Play Store (Beta channel)
- GitHub Releases (automatic)
- F-Droid (Community fork)

**Success Metrics:**
- 5,000+ beta testers
- <1x crash per session
- 95%+ onboarding success rate
- Common packages install successfully
- Health check catches 80%+ of issues

**Deprecation Notices:**
- Phase 8B: Backend migration plan announced
- External storage default: "migrating to app-private in v1.0"

**Support:** Community forums, regular office hours, bug bounty program

---

### Release Candidate (Months 8-9): Pre-Release Polish
**Target:** Final stability fixes, translation work

**Version:** `0.9.0-RC.1` through `0.9.0-RC.3` (weekly releases)

**Features:** Phase 13-14 complete (Performance UI, Test Suite)

**Stability:** Production-ready, security audited

**Distribution:** Google Play Store (Release Candidate channel)

**Success Metrics:**
- 10,000+ test installs
- Zero critical bugs
- 99%+ boot success rate
- Health check validates all 5 components

**Deprecation Timeline Finalized:**
- External storage default ends in v1.0
- Old onboarding format no longer supported
- Phase 8B Debian backend migration: schedule announced

---

### Stable Release (Month 10): v1.0.0 Production
**Target:** Wide audience, production SLA

**Version:** `1.0.0` (first stable release)

**All Phases Complete:** 1-14 fully implemented

**Stability:** Production grade

**Distribution:**
- Google Play Store (primary)
- F-Droid (community)
- GitHub Releases
- Direct APK (manual)

**Breaking Changes (from alpha):**
- External storage workspace no longer default
- Old onboarding state format invalidates (users must re-onboard)
- `DEVPOCKET_*` environment variables now required for terminal

**Support SLA:**
- Critical bugs: 48-hour response
- Security issues: 24-hour response
- Feature requests: monthly review

**Success Metrics for v1.0:**
- 50,000+ installs in first month
- 4.5+ star rating (Play Store)
- <0.5x crashes per session (production monitoring)
- 99.5% uptime (backend services)

---

### Maintenance Phase (Month 11+): Stability & Security
**Target:** Long-term support, incremental improvements

**Version:** `1.0.x`, `1.1.0`, etc. (monthly patch, quarterly minor)

**Focus:**
- Security updates (rapid deployment)
- Performance optimization
- Device compatibility expansion
- Community contributions

**Phase 8B Decision:** Backend migration to inside Debian (optional, not blocking)

**Deprecation Policy:**
- Announce breaking changes 6 months in advance
- Maintain compatibility for 1 full year post-deprecation
- Example: External storage workspace support continues until v2.0 (estimated M+18)

---

## Upgrade Path for Users

### Alpha → Beta
```
Alpha User (v0.1.0)
       ↓
Auto-prompt: "Beta channel available"
       ↓
Opt-in to beta in settings
       ↓
Download and install v0.2.0-beta.1
       ↓
Onboarding state preserved ✓
Projects preserved ✓
```

### Beta → RC
```
Beta User (v0.2.x)
       ↓
Staged rollout: 25% → 50% → 100%
       ↓
Auto-prompt at v0.9.0
       ↓
Install RC (v0.9.0-RC.1)
       ↓
Health check & repair if needed
```

### RC → Stable
```
RC User (v0.9.0-RC.x)
       ↓
Automatic upgrade to v1.0.0
       ↓
Validation: All projects accessible
       ↓
Success notification
```

---

## Deprecation Strategy

### When Deprecating a Feature

1. **Announcement Phase (6 months):**
   - Feature marked deprecated in code/UI
   - Warning notification to users
   - Blog post + Discord announcement
   - Migration guide provided

2. **Compatibility Phase (12 months):**
   - Legacy code path still functional
   - All new features skip legacy code
   - Telemetry tracks usage

3. **Removal Phase:**
   - Major version bump (e.g., v1.0 → v2.0)
   - Legacy code deleted
   - Final migration guide published

### Example: External Storage Workspace

| Version | Action |
|---------|--------|
| v0.1-0.9 | Default to external storage workspace |
| v0.5 | Announce: "Moving to app-private in v1.0" |
| v1.0 | **BREAKING:** External storage no longer default |
| v1.0-1.x | Still supports external storage if explicitly set |
| v2.0 | External storage support removed entirely |

---

## Security Release Process

**Critical security vulnerability discovered?**

1. **Patch (same day):**
   - Code review & fix
   - Automated tests pass
   - Immediate release: v1.0.1

2. **Deployment (rollout):**
   - 25% staged rollout (1 hour)
   - Monitor crash rates
   - 100% rollout if safe

3. **Communication:**
   - GitHub security advisory
   - Discord #security-updates
   - Email notification (registered users)

**Example Timeline:**
```
14:00 UTC: Vulnerability reported
14:30 UTC: Fix verified
15:00 UTC: v1.0.1 released to 25% of users
16:00 UTC: Expanded to 100% (no issues)
16:30 UTC: Public security advisory posted
```

---

## Platform-Specific Rollout

### Android Devices

| Device Type | Rollout Strategy | Testing Focus |
|-------------|------------------|---------------|
| Flagship (Pixel, Samsung S) | 100% immediate | Features |
| Mid-range (Pixel a, Moto G) | 50% → 100% | Performance |
| Budget (<$200) | 25% → 100% | Stability |
| Old (API 24-26) | Manual testing only | Compatibility |

### Architecture Support

| Arch | Support Status | Notes |
|------|----------------|-------|
| arm64-v8a | Full | Primary target |
| armv7 | Partial (v1.1) | Reduced feature set |
| x86_64 | Emulator only | Testing only |
| x86 | Not supported | Discontinued in v1.0 |

---

## Community Feedback Integration

### Alpha Phase

**Weekly Surveys:**
- 5 questions: features, stability, usability
- In-app notification, 2-minute time estimate
- Incentive: early access to beta features

**Discord Channels:**
- `#alpha-bugs` - bug reports with automatic reply bot
- `#alpha-features` - feature requests
- `#alpha-general` - discussion

**Issue Triage:** Manual within 48 hours

### Beta Phase

**Play Store Reviews:** Monitored daily
- Reply to 100% of 1-2 star reviews within 24h
- Offer troubleshooting / escalate to devs

**Community Roadmap:** Public GitHub Project Board
- Users vote on features (upvote reactions)
- ETA and progress tracked

**Monthly Community Call:** Video meeting with 50-200 participants

### Stable Phase

**Feature Voting:** Annual community survey
- Prioritizes Phase 8B backend migration
- Guides v1.1, v1.2 road map

---

## Success Criteria for Each Phase

### Alpha Success
- ✅ No critical crashes
- ✅ Onboarding works on 5+ devices
- ✅ Terminal executes commands
- ✅ 100+ active testers engaged

### Beta Success
- ✅ <0.5% crash rate
- ✅ Package installations succeed
- ✅ Health check reliably detects issues
- ✅ 5,000+ beta testers

### Release Candidate Success
- ✅ Zero critical bugs
- ✅ Security audit passed
- ✅ 99%+ stability
- ✅ 10,000+ test installs

### v1.0 Stable Success
- ✅ 50,000+ installs (month 1)
- ✅ 4.5+ star rating
- ✅ Production SLA met
- ✅ Profitable (ads / premium tier support)

---

## Post-1.0 Roadmap Sketch

**v1.1 (Month 13):** armv7 support, offline mode expansion

**v1.2 (Month 14):** Phase 8B backend migration (optional)

**v2.0 (Month 18):** Complete redesign with native UI components

---

## Rollout Failure Recovery

| Scenario | Recovery |
|----------|----------|
| Rollout crash rate >5% | Pause, revert, investigate |
| Security issue mid-rollout | Pause all rollouts, patch immediately |
| File corruption reported | Halt rollout, provide repair tool |
| Device incompatibility | Exclude model from rollout, dev specific fix |

**Post-Mortem Process:**
1. Root cause analysis (24 hours)
2. Fix development & testing (48 hours)
3. Rollout resume with 25% staged approach
4. Public post-mortem published (weekly meeting)

---

## Deprecation & EOL Policy

**Long-Term Support (LTS) versions:**
- v1.0.x: Supported until v2.0 release + 6 months
- v1.x (minor patches): Supported for 12 months
- v2.x: Pattern repeats

**Minimum supported Android:** API 24 (Android 7.0)
- Phased deprecation: v1.0 supports 24+, v1.5 supports 26+, v2.0 supports 28+
- Each deprecation announced 12 months ahead

---

## Financial Sustainability

Phase 15 includes monetization planning:

1. **Free Tier (current):** DevPocket app free forever
   - Supports core development, hosting, infrastructure

2. **Premium Tier (v1.5):** Optional (not blocking features)
   - Advanced package management UI
   - Cloud project sync (coming later)
   - Priority support
   - Estimated: $2.99 / month or $19.99 / year

3. **Team Edition (v2.0):** For organizations
   - Multi-user team projects
   - Centralized management
   - Estimated: $50 / month / team

4. **Revenue Share:** Community contributors get 20% revenue cut from premium features they implement

---

## Go-Live Checklist (v1.0)

- [ ] All phases 1-14 implemented and tested
- [ ] Security audit completed
- [ ] Privacy policy finalized
- [ ] Support infrastructure ready (forum, email)
- [ ] App Store & F-Droid submissions approved
- [ ] Launch video published
- [ ] Press release drafted
- [ ] Community announcement scheduled
- [ ] Monitoring & metrics dashboard deployed
- [ ] Rollback procedure documented and tested

**Launch Date:** Month 10, Week 1 (estimated)

