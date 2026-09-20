# ashlar.wujm.cc

Static website for Ashlar: `index.html` (English), `zh/index.html` (Chinese), one shared `style.css`, images in `assets/`. No build step. The hero's version badge and download link come from the GitHub releases API at page load and degrade to the releases page when it is unreachable.

Deploy (CloudPanel static site, site user `wujm-ashlar`):

```sh
rsync -az --delete site/ myserver:/home/wujm-ashlar/htdocs/ashlar.wujm.cc/
ssh myserver chown -R wujm-ashlar:wujm-ashlar /home/wujm-ashlar/htdocs/ashlar.wujm.cc
```

Keep both language pages in step with each other and with the two READMEs.
