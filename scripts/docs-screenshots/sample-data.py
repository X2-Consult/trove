"""Sample data for the docs instance, set through Trove's API: book details (titles, series, first
publication years, genres and one-line descriptions written for these screenshots), short author
biographies, and a few months of reading history so the statistics pages have something to show.

Run once, after the library has been created and scanned:
  TROVE_DOCS_URL=http://localhost:6061 TROVE_DOCS_PASSWORD=... python3 sample-data.py

Book details and biographies can be set again safely; reading history is added each time it runs,
so pass --no-history to update only the details.
"""
import json
import os
import random
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = os.environ.get('TROVE_DOCS_URL', 'http://localhost:6061').rstrip('/') + '/api/v1'
USERNAME = os.environ.get('TROVE_DOCS_USERNAME', 'elinor')


def request(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    with urllib.request.urlopen(req) as r:
        text = r.read()
        return json.loads(text) if text else None


TOKEN = request('POST', '/auth/login', {'username': USERNAME, 'password': os.environ['TROVE_DOCS_PASSWORD']})['accessToken']


def call(method, path, body=None):
    return request(method, path, body, TOKEN)


# Book details, matched on the start of the title (the one embedded in the file, or the one set here)
# -> (title, authors, year, series, number, genres, description)
S = 'Sherlock Holmes'; OZ = 'Oz'; ANNE = 'Anne of Green Gables'; ALICE = 'Alice'
BOOKS = {
    'A Study in Scarlet': ('A Study in Scarlet', ['Arthur Conan Doyle'], 1887, S, 1, ['Mystery', 'Detective'], 'Dr. Watson meets an eccentric consulting detective, and the pair take on a revenge killing with roots in the American West.'),
    'The Sign of the Four': ('The Sign of the Four', ['Arthur Conan Doyle'], 1890, S, 2, ['Mystery', 'Detective'], 'A young woman receives a pearl every year from an unknown sender, and Holmes follows the trail to a stolen treasure.'),
    'The Adventures of Sherlock Holmes': ('The Adventures of Sherlock Holmes', ['Arthur Conan Doyle'], 1892, S, 3, ['Mystery', 'Short Stories'], 'Twelve cases from Baker Street, from a missing king\'s photograph to a band that is not what it seems.'),
    'The Memoirs of Sherlock Holmes': ('The Memoirs of Sherlock Holmes', ['Arthur Conan Doyle'], 1894, S, 4, ['Mystery', 'Short Stories'], 'More cases, ending with Holmes\'s confrontation with Professor Moriarty at the Reichenbach Falls.'),
    'The Hound of the Baskervilles': ('The Hound of the Baskervilles', ['Arthur Conan Doyle'], 1902, S, 5, ['Mystery', 'Gothic'], 'A family legend of a spectral hound on Dartmoor, and a death that seems to prove it true.'),
    'The Return of Sherlock Holmes': ('The Return of Sherlock Holmes', ['Arthur Conan Doyle'], 1905, S, 6, ['Mystery', 'Short Stories'], 'Holmes is back in London, and the cases resume.'),
    'The Wonderful Wizard of Oz': ('The Wonderful Wizard of Oz', ['L. Frank Baum'], 1900, OZ, 1, ['Fantasy', 'Children\'s'], 'A cyclone carries Dorothy to Oz, where she sets off down the yellow brick road to find a way home.'),
    'The Marvelous Land of Oz': ('The Marvelous Land of Oz', ['L. Frank Baum'], 1904, OZ, 2, ['Fantasy', 'Children\'s'], 'A boy named Tip escapes a witch and falls in with the Scarecrow as an army marches on the Emerald City.'),
    'Ozma of Oz': ('Ozma of Oz', ['L. Frank Baum'], 1907, OZ, 3, ['Fantasy', 'Children\'s'], 'Shipwrecked with a talking hen, Dorothy reaches the land of Ev and helps free its royal family.'),
    'Anne of Green Gables': ('Anne of Green Gables', ['L. M. Montgomery'], 1908, ANNE, 1, ['Coming of Age', 'Classics'], 'An imaginative orphan girl arrives by mistake at a Prince Edward Island farm and wins over everyone she meets.'),
    'Anne of Avonlea': ('Anne of Avonlea', ['L. M. Montgomery'], 1909, ANNE, 2, ['Coming of Age', 'Classics'], 'Anne, now sixteen, becomes the village schoolteacher.'),
    'Anne of the Island': ('Anne of the Island', ['L. M. Montgomery'], 1915, ANNE, 3, ['Coming of Age', 'Classics'], 'Anne leaves Avonlea for college in Kingsport.'),
    "Alice's Adventures in Wonderland": ("Alice's Adventures in Wonderland", ['Lewis Carroll'], 1865, ALICE, 1, ['Fantasy', 'Children\'s'], 'Alice follows a white rabbit down a hole into a world of nonsense and riddles.'),
    'Through the Looking-Glass': ('Through the Looking-Glass', ['Lewis Carroll'], 1871, ALICE, 2, ['Fantasy', 'Children\'s'], 'Alice climbs through a mirror into a land laid out like a chessboard.'),
    'Pride and Prejudice': ('Pride and Prejudice', ['Jane Austen'], 1813, None, None, ['Romance', 'Classics'], 'Elizabeth Bennet and Mr. Darcy misjudge each other badly before they understand each other at all.'),
    'Emma': ('Emma', ['Jane Austen'], 1815, None, None, ['Romance', 'Classics'], 'A young woman who fancies herself a matchmaker learns how little she understands the hearts around her.'),
    'Frankenstein': ('Frankenstein', ['Mary Shelley'], 1818, None, None, ['Gothic', 'Science Fiction'], 'A young scientist gives life to a creature and then abandons it, with terrible consequences.'),
    'Moby-Dick': ('Moby-Dick', ['Herman Melville'], 1851, None, None, ['Adventure', 'Classics'], 'Ishmael signs on to a whaling ship whose captain is obsessed with one white whale.'),
    'Moby Dick': ('Moby-Dick', ['Herman Melville'], 1851, None, None, ['Adventure', 'Classics'], 'Ishmael signs on to a whaling ship whose captain is obsessed with one white whale.'),
    'Dracula': ('Dracula', ['Bram Stoker'], 1897, None, None, ['Horror', 'Gothic'], 'Told in letters and diaries: a count from Transylvania arrives in England, and a small band sets out to stop him.'),
    'The Time Machine': ('The Time Machine', ['H. G. Wells'], 1895, None, None, ['Science Fiction'], 'A Victorian inventor travels to the year 802,701 and finds humanity split in two.'),
    'The War of the Worlds': ('The War of the Worlds', ['H. G. Wells'], 1898, None, None, ['Science Fiction'], 'Martian cylinders land in Surrey, and a narrator struggles to survive the invasion that follows.'),
    'The war of the worlds': ('The War of the Worlds', ['H. G. Wells'], 1898, None, None, ['Science Fiction'], 'Martian cylinders land in Surrey, and a narrator struggles to survive the invasion that follows.'),
    'Treasure Island': ('Treasure Island', ['Robert Louis Stevenson'], 1883, None, None, ['Adventure'], 'Young Jim Hawkins sails for buried treasure with a crew that includes the one-legged Long John Silver.'),
    'A Tale of Two Cities': ('A Tale of Two Cities', ['Charles Dickens'], 1859, None, None, ['Historical Fiction', 'Classics'], 'Lives in London and Paris are drawn into the French Revolution.'),
    'Great Expectations': ('Great Expectations', ['Charles Dickens'], 1861, None, None, ['Coming of Age', 'Classics'], 'An orphan named Pip receives a fortune from a mysterious benefactor.'),
    'The Picture of Dorian Gray': ('The Picture of Dorian Gray', ['Oscar Wilde'], 1890, None, None, ['Gothic', 'Classics'], 'A young man stays beautiful while his portrait ages and records every sin.'),
    'Jane Eyre': ('Jane Eyre', ['Charlotte Brontë'], 1847, None, None, ['Romance', 'Gothic'], 'An orphaned governess finds love at Thornfield Hall, and a secret kept in its attic.'),
    'Little Women': ('Little Women', ['Louisa May Alcott'], 1868, None, None, ['Coming of Age', 'Classics'], 'The four March sisters grow up in New England during the Civil War.'),
    'The call of the wild': ('The Call of the Wild', ['Jack London'], 1903, None, None, ['Adventure'], 'Buck, a domestic dog, is stolen and sold into service as a sled dog in the Klondike.'),
    'The Jungle Book': ('The Jungle Book', ['Rudyard Kipling'], 1894, None, None, ['Children\'s', 'Short Stories'], 'Stories of Mowgli, raised by wolves in the Indian jungle, and other animal tales.'),
    'Peter Pan': ('Peter Pan', ['J. M. Barrie'], 1911, None, None, ['Fantasy', 'Children\'s'], 'Wendy and her brothers fly away to Neverland with the boy who won\'t grow up.'),
    'Twenty Thousand Leagues': ('Twenty Thousand Leagues Under the Sea', ['Jules Verne'], 1870, None, None, ['Science Fiction', 'Adventure'], 'A professor is taken aboard Captain Nemo\'s submarine, the Nautilus.'),
    'Around the World in Eighty Days': ('Around the World in Eighty Days', ['Jules Verne'], 1872, None, None, ['Adventure'], 'Phileas Fogg bets his fortune that he can circle the globe in eighty days.'),
    'The Art of War': ('The Art of War', ['Sun Tzu'], 1910, None, None, ['Philosophy', 'History'], 'The ancient Chinese treatise on strategy, in Lionel Giles\'s 1910 translation.'),
    'Happy Prince': ('The Happy Prince and Other Tales', ['Oscar Wilde'], 1888, None, None, ['Fairy Tales', 'Short Stories'], 'Five fairy tales, read by LibriVox volunteers.'),
    'The Marvelous Land of Oz Picture Book': ('The Marvelous Land of Oz Picture Book', ['L. Frank Baum', 'John R. Neill'], 1904, 'Oz Picture Books', 1, ['Fantasy', 'Illustrated'], "John R. Neill's illustrations for The Marvelous Land of Oz."),
}

# withDescription: the metadata is sent back whole, and a list without descriptions would erase them.
books = call('GET', '/books?withDescription=true')
for book in books:
    meta = book.get('metadata') or {}
    title = meta.get('title') or ''
    key = max((k for k in BOOKS if title.startswith(k)), key=len, default=None)
    if not key:
        continue
    t, authors, year, series, number, cats, desc = BOOKS[key]
    meta.update({'title': t, 'authors': authors, 'publishedDate': f'{year}-01-01', 'categories': cats,
                 'description': desc, 'language': 'en', 'seriesName': series, 'seriesNumber': number})
    if 'Happy Prince' in t:
        meta['narrator'] = 'LibriVox volunteers'
    call('PUT', f"/books/{book['id']}/metadata", {'metadata': meta, 'clearFlags': {}})
print('book details set')

BIOS = {
    'Arthur Conan Doyle': 'Scottish physician and writer (1859-1930), best known for the Sherlock Holmes stories, which he wrote across four decades alongside historical novels, science fiction and plays.',
    'Jane Austen': 'English novelist (1775-1817) whose six novels, from Sense and Sensibility to Persuasion, look at marriage, money and manners among the English gentry.',
    'L. Frank Baum': "American author (1856-1919) who wrote The Wonderful Wizard of Oz and thirteen more Oz books, along with dozens of other children's stories.",
    'L. M. Montgomery': 'Canadian author (1874-1942) from Prince Edward Island, whose Anne of Green Gables began a series of eight Anne books.',
    'H. G. Wells': 'English writer (1866-1946) whose early scientific romances, including The Time Machine and The War of the Worlds, helped shape modern science fiction.',
    'Charles Dickens': 'English novelist (1812-1870) whose serialised novels, from Oliver Twist to Great Expectations, were read across the world in his lifetime.',
}
for author in call('GET', '/authors'):
    if author['name'] in BIOS:
        call('PUT', f"/authors/{author['id']}", {'name': author['name'], 'description': BIOS[author['name']]})
# Renamed books can leave the name from the file behind as an author with no books.
orphans = [a['id'] for a in call('GET', '/authors') if a.get('bookCount') == 0]
if orphans:
    call('DELETE', '/authors', orphans)
print('author biographies set')

if '--no-history' in sys.argv:
    sys.exit()

# Reading history: sessions over the past months, final progress, status and rating per book.
ids = {b['metadata']['title']: b['id'] for b in call('GET', '/books?stripForListView=true')}
random.seed(1813)
now = datetime.now(timezone.utc).replace(microsecond=0)
# title, type, first day (days ago), last day (days ago), final progress %, status, rating
PLAN = [
    ("Alice's Adventures in Wonderland", 'EPUB', 150, 138, 100, 'READ', 5),
    ('The Time Machine', 'EPUB', 128, 120, 100, 'READ', 4),
    ('The Wonderful Wizard of Oz', 'EPUB', 110, 97, 100, 'READ', 4),
    ('The Call of the Wild', 'EPUB', 92, 85, 100, 'READ', 3),
    ('Frankenstein', 'EPUB', 80, 58, 100, 'READ', 5),
    ('Treasure Island', 'EPUB', 55, 38, 100, 'READ', 4),
    ('The Art of War', 'PDF', 45, 20, 55, 'READING', None),
    ('Pride and Prejudice', 'EPUB', 30, 1, 64, 'READING', None),
    ('The Hound of the Baskervilles', 'EPUB', 12, 2, 38, 'READING', None),
    ('Dracula', 'EPUB', 9, 3, 22, 'READING', None),
    ('The Marvelous Land of Oz Picture Book', 'CBX', 6, 4, 30, 'READING', None),
    ('The Happy Prince and Other Tales', 'AUDIOBOOK', 5, 0, 40, 'READING', None),
]

sessions = 0
for title, kind, first, last, final, status, rating in PLAN:
    book = ids[title]
    count = max(2, (first - last) // 3)
    progress = 0.0
    days = sorted(random.sample(range(last, first + 1), min(count, first - last + 1)), reverse=True)
    for i, day in enumerate(days):
        start = (now - timedelta(days=day)).replace(hour=random.choice([7, 12, 19, 20, 21, 22]), minute=random.randint(0, 59), second=0)
        minutes = random.randint(15, 75) if kind != 'CBX' else random.randint(8, 20)
        end = start + timedelta(minutes=minutes)
        target = final * (i + 1) / len(days)
        call('POST', '/reading-sessions', {
            'bookId': book, 'bookType': kind, 'startTime': start.isoformat().replace('+00:00', 'Z'),
            'endTime': end.isoformat().replace('+00:00', 'Z'), 'durationSeconds': minutes * 60,
            'durationFormatted': f'{minutes // 60}h {minutes % 60}m', 'startProgress': round(progress, 1), 'endProgress': round(target, 1)})
        progress = target
        sessions += 1
    body = {'bookId': book}
    if kind == 'EPUB':
        body['epubProgress'] = {'cfi': None, 'href': None, 'percentage': final}
    elif kind == 'PDF':
        body['pdfProgress'] = {'page': max(1, int(final)), 'percentage': final}
    elif kind == 'CBX':
        body['cbxProgress'] = {'page': 7, 'percentage': final}
    if kind != 'AUDIOBOOK':
        call('POST', '/books/progress', body)
    call('POST', '/books/status', {'bookIds': [book], 'status': status})
    if rating:
        call('PUT', '/books/personal-rating', {'ids': [book], 'rating': rating})
# a couple of statuses without sessions, so the filters and stats have some variety
call('POST', '/books/status', {'bookIds': [ids['Moby-Dick']], 'status': 'PAUSED'})
call('POST', '/books/status', {'bookIds': [ids['Emma'], ids['Jane Eyre'], ids['Little Women']], 'status': 'UNREAD'})
print('sessions created:', sessions)
