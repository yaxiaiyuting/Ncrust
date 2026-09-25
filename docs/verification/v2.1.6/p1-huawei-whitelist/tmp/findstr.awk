/^Class descriptor/{cls=$0}
/^      name          : /{nm=$0}
/not in media white list|isInMediaWhiteList|MediaControlUtils/{
  if ($0 ~ /^[0-9a-f]+:.*const-string/) print cls" | "nm" | "$0
}
