package main

import (
 "crypto/hmac"
 "crypto/rand"
 "crypto/sha256"
 "encoding/base64"
 "encoding/hex"
 "errors"
 "os"
 "path/filepath"
 "strings"
)

// V2VehicleIdentity pseudonymizes VINs in ordinary archives and reports.
// The 256-bit private salt must be included in a protected SERVER backup,
// never in a downloadable report or Android response.
type V2VehicleIdentity struct { salt []byte }

func OpenV2VehicleIdentity(dir string)(*V2VehicleIdentity,error){
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 path:=filepath.Join(dir,"v2-vehicle-key")
 content,err:=os.ReadFile(path)
 if errors.Is(err,os.ErrNotExist){
  key:=make([]byte,32)
  if _,err=rand.Read(key);err!=nil{return nil,err}
  file,e:=os.OpenFile(path,os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600)
  if errors.Is(e,os.ErrExist){content,err=os.ReadFile(path)}else if e!=nil{return nil,e}else{
   encoded:=base64.RawStdEncoding.EncodeToString(key)
   _,err=file.WriteString(encoded)
   if e2:=file.Sync();err==nil{err=e2}
   if e2:=file.Close();err==nil{err=e2}
   if err!=nil{return nil,err}
   content=[]byte(encoded)
  }
 }else if err!=nil{return nil,err}
 key,err:=base64.RawStdEncoding.DecodeString(strings.TrimSpace(string(content)))
 if err!=nil || len(key)!=32{return nil,errors.New("chiave identificazione veicoli V2 non valida")}
 return &V2VehicleIdentity{salt:key},nil
}

func (v *V2VehicleIdentity) ID(vin string) VehicleID {
 if v==nil || len(v.salt)!=32 || strings.TrimSpace(vin)=="" {return ""}
 mac:=hmac.New(sha256.New,v.salt)
 _,_=mac.Write([]byte(strings.ToUpper(strings.TrimSpace(vin))))
 return VehicleID(hex.EncodeToString(mac.Sum(nil)[:16]))
}
