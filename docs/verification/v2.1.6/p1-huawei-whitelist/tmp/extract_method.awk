# usage: awk -v M='methodName' -f extract_method.awk dis.txt
BEGIN{ inb=0 }
/^    #[0-9]+ +: \(in /{ hdr=$0; getline; }
{
  if ($0 ~ /^      name          : /) {
    nm=$0; sub(/^      name          : /,"",nm); gsub(/'/,"",nm)
    if (nm==M) { inb=1; print "----- METHOD " M " -----"; print hdr; print "      name          : '" nm "'"; next }
    else if (inb==1) { inb=0 }
  }
  if (inb==1) print
}
