# A window with Google's sign-in page, for seraph-play-login, as the Seraph app's sign-in screen is on the phone: the
# page is this window's own, so the cookie Google sets once the account signs in (oauth_token, a token that lasts
# minutes) can be read here, with no copying from a browser's developer tools. Prints the account's email address
# and that token, one a line, and ends; ends with status 1 when the window is closed first. Nothing is kept: the
# window's browser is a private one, gone when it ends. Needs PyQt6 with Qt WebEngine.
import sys

from PyQt6.QtCore import QUrl
from PyQt6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWidgets import QApplication

PAGE = "https://accounts.google.com/EmbeddedSetup"
# The page is the one Android's setup shows: it is asked for as an Android phone's browser view asks for it.
AGENT = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
         "Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36")
# The account's email address, as the page shows it once it is signed in.
EMAIL = ("(function() { var e = document.querySelector('[data-profile-identifier][data-email]');"
         " return e ? e.getAttribute('data-email') : null; })();")

app = QApplication(sys.argv)
profile = QWebEngineProfile()  # no name: private, nothing written to disk
profile.setHttpUserAgent(AGENT)
page = QWebEnginePage(profile)
view = QWebEngineView()
view.setPage(page)
view.setWindowTitle("Sign in to Google Play (seraph-play-login)")
view.resize(480, 820)
found = {}


def finish(email):
    print(email if isinstance(email, str) and "@" in email else "")
    print(found["token"])
    sys.stdout.flush()
    app.quit()


def added(cookie):
    if found or bytes(cookie.name()) != b"oauth_token":
        return
    found["token"] = bytes(cookie.value()).decode()
    page.runJavaScript(EMAIL, finish)


profile.cookieStore().cookieAdded.connect(added)
view.load(QUrl(PAGE))
view.show()
app.exec()
# The page before the profile, as Qt wants them gone.
view.setPage(None)
del page
sys.exit(0 if found else 1)
