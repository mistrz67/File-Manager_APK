# Dyski sieciowe w menedżerze plików (SMB, SFTP, FTP/FTPS)

Ten fork Fossify File Manager ma wbudowaną obsługę dysków sieciowych z **zapamiętanymi danymi logowania**.

## Jak zdobyć APK

1. Wejdź w zakładkę **Actions** repozytorium → workflow **Build APK** → wybierz najnowszy przebieg (zielony ✔).
2. Na dole strony przebiegu, w sekcji **Artifacts**, pobierz `file-manager-apk` (ZIP z plikiem `.apk`).
3. Rozpakuj i zainstaluj APK na telefonie (system poprosi o zgodę na instalację z nieznanych źródeł).

Budowę można też uruchomić ręcznie: **Actions → Build APK → Run workflow**. Opcja „Publish a release” dodatkowo wrzuca APK do zakładki **Releases**.

### Podpis APK (zalecane)

Bez własnego klucza APK jest podpisane tymczasowym kluczem debugowym — zainstaluje się, ale **kolejnej wersji nie da się zainstalować „na wierzch”** (trzeba odinstalować starą, a wraz z nią znikną zapisane dyski). Aby tego uniknąć, wygeneruj własny klucz i dodaj w repozytorium (**Settings → Secrets and variables → Actions**) cztery sekrety:

| Sekret | Zawartość |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | plik keystore zakodowany base64, np. `base64 -w0 moj.keystore` |
| `SIGNING_STORE_PASSWORD` | hasło keystore |
| `SIGNING_KEY_ALIAS` | alias klucza |
| `SIGNING_KEY_PASSWORD` | hasło klucza |

Klucz można utworzyć poleceniem:
`keytool -genkeypair -v -keystore moj.keystore -alias moj -keyalg RSA -keysize 4096 -validity 10000`
(**zachowaj plik i hasła** — bez nich nie zaktualizujesz aplikacji).

## Użycie

1. Menu główne (⋮) → **Dyski sieciowe** → **Dodaj dysk sieciowy**. Gdy masz już jakiś dysk, pojawia się on także w oknie wyboru pamięci (obok pamięci wewnętrznej i karty SD), a pozycja **Zarządzaj dyskami sieciowymi…** prowadzi do tej listy.
2. Wybierz typ: **SMB** (udział Windows / NAS), **SFTP (SSH)**, **FTP**, **FTPS (jawny TLS)** lub **FTPS (niejawny TLS)**. Wpisz adres serwera, ewentualnie port, a potem sposób logowania: *nazwa użytkownika i hasło*, *klucz prywatny* (SFTP) albo *anonimowo / gość*. Dla SMB nazwa udziału jest opcjonalna — puste pole pokaże wszystkie udziały serwera. Przycisk **Skanuj sieć** wyszukuje serwery w lokalnej sieci (mDNS + skan portów 445/22/21).
3. **Przetestuj połączenie**, a potem zapisz. Dysk pojawia się na liście pamięci — dalej nie wpisujesz już hasła.

Co można robić na dysku: przeglądać foldery, otwierać pliki (pobierane do pamięci podręcznej), edytować i zapisywać z powrotem pliki tekstowe, udostępniać, zmieniać nazwy, tworzyć foldery, sprawdzać właściwości, wyszukiwać w bieżącym folderze oraz **kopiować, przenosić i usuwać** między telefonem a dyskiem. Transfery działają w tle (usługa pierwszoplanowa, powiadomienie z postępem i przyciskiem „Anuluj”), a przy konflikcie nazw pytają: nadpisać / pominąć / zachować obie.

## Bezpieczeństwo

- Hasła i klucze prywatne są szyfrowane AES-256-GCM kluczem z **Android Keystore** (klucz nie opuszcza urządzenia) i **nie trafiają do kopii zapasowych** (`backup_rules.xml`, `data_extraction_rules.xml`).
- **Zaufanie przy pierwszym połączeniu (TOFU):** odcisk klucza hosta SSH / certyfikatu FTPS jest pokazywany do zatwierdzenia i zapamiętywany. Jeśli później się zmieni, połączenie jest odrzucane z ostrzeżeniem (możliwy atak „man in the middle”), dopóki świadomie go nie zaakceptujesz.
- Zwykły FTP i SMB bez podpisywania nie szyfrują ruchu w całości — używaj ich tylko w zaufanej sieci; do internetu wybieraj SFTP lub FTPS.
- Gdy po przywróceniu telefonu klucz Keystore jest niedostępny, aplikacja poprosi o ponowne wpisanie hasła (dysk i ustawienia zostają); to samo dotyczy sytuacji, gdy serwer odrzuci zapisane hasło.

## Ograniczenia

- SMB: obsługiwane SMB 2/3 (SMB1 nie). Logowanie gościa/anonimowe jest dostępne.
- Brak miniatur zdjęć z dysków i brak strumieniowania mediów (pliki są najpierw pobierane do pamięci podręcznej, która jest czyszczona po 24 h).
- Wyszukiwanie na dysku filtruje tylko bieżący folder.
- FTPS: współdzielenie sesji TLS na kanale danych (wymagane m.in. przez vsftpd z `require_ssl_reuse`) zostało zweryfikowane tylko z Conscrypt na JVM; na urządzeniach zależy od wersji Androida — w razie kłopotów użyj SFTP.
- Android 17+: system może wymagać przyznania uprawnienia „urządzenia w sieci lokalnej” dla wykrywania serwerów.

## Co zostało sprawdzone

Warstwa protokołów była testowana na prawdziwych serwerach (Samba, OpenSSH, vsftpd) i na serwerach wbudowanych w testy (Apache MINA SSHD, Apache FtpServer); ekrany i operacje na plikach — testami Robolectric. Testy uruchamiają się przy każdym budowaniu w GitHub Actions. Nie było możliwości uruchomienia aplikacji na prawdziwym urządzeniu/emulatorze, więc pierwszy test „na żywo” na telefonie jest po Twojej stronie — błędy zgłaszaj w Issues.

## Google Drive (plan na drugi etap)

Integracja z Google Drive nie jest jeszcze wykonana. Wymaga **własnego klienta OAuth** w Google Cloud Console (projekt, włączone Drive API, klient typu Android z odciskiem SHA-1 Twojego klucza podpisu). Zakres `drive` jest „restricted” — dla własnego użytku wystarczy tryb *Testing* z Twoim kontem jako użytkownikiem testowym, ale **tokeny odświeżania wygasają wtedy po 7 dniach** (trzeba logować się ponownie); trwałe rozwiązanie wymaga weryfikacji aplikacji przez Google albo ograniczenia do zakresu `drive.file` (tylko pliki utworzone przez aplikację). Dysk Google dodamy jako kolejny typ „dysku” obok SMB/SFTP/FTP, korzystając z tej samej listy, zapisu danych i silnika transferów.
