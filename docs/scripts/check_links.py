from argparse import ArgumentParser
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import json


class Page(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.links = []
        self.ids = set()
        self.feed(source)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if "id" in attributes:
            self.ids.add(attributes["id"])
        if tag == "a" and "href" in attributes:
            self.links.append(attributes["href"])
        if tag == "img" and "src" in attributes:
            self.links.append(attributes["src"])


def check(dist, base):
    pages = {path.resolve(): Page(path.read_text(encoding="utf-8"))
             for path in dist.rglob("*.html")}
    failures = []
    checked = 0
    for source, page in pages.items():
        for link in page.links:
            url = urlsplit(link)
            if url.scheme or url.netloc:
                continue
            path = unquote(url.path)
            if path.startswith(base):
                target = dist / path[len(base):]
            elif path.startswith("/"):
                target = dist / path.lstrip("/")
            elif path:
                target = source.parent / path
            else:
                target = source
            target = target.resolve()
            if target.is_dir():
                target /= "index.html"
            elif not target.is_file() and not target.suffix:
                target = target.with_suffix(".html")
            checked += 1
            reason = None
            if not target.is_file():
                reason = "missing file"
            elif url.fragment and target.suffix == ".html":
                if unquote(url.fragment) not in pages[target.resolve()].ids:
                    reason = "missing anchor"
            if reason:
                failures.append({"page": str(source.relative_to(dist)), "link": link, "reason": reason})
    return {"pages": len(pages), "checked": checked, "failures": failures}


def main():
    parser = ArgumentParser(description="Check built site links, anchors and images without network requests.")
    parser.add_argument("--dist", type=Path, default=Path(__file__).resolve().parents[1] / ".vitepress/dist")
    parser.add_argument("--base", default="/core-ai/")
    parser.add_argument("--json", type=Path)
    arguments = parser.parse_args()
    dist = arguments.dist.resolve()
    if not (dist / "index.html").is_file():
        parser.error(f"Build the VitePress site first: {dist}")
    result = check(dist, arguments.base)
    if arguments.json:
        arguments.json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Checked {result['pages']} pages and {result['checked']} links/images; {len(result['failures'])} failures.")
    for failure in result["failures"]:
        print(f"{failure['page']}: {failure['link']} ({failure['reason']})")
    return bool(result["failures"])


if __name__ == "__main__":
    raise SystemExit(main())
