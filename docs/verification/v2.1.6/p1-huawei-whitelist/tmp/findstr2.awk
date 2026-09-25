/Class descriptor/{cls=$0}
/^      name          : /{nm=$0}
/not in media white list/{ print cls" ||| "nm" ||| "$0 }
