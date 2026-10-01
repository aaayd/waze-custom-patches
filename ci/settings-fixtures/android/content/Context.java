package android.content;
public class Context {
 public String saved;
 public SharedPreferences getSharedPreferences(String name,int mode) {
  return (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (p,m,a)-> {
   if(m.getName().equals("getString")) return "icon_pack".equals(a[0]) && saved!=null ? saved : a[1];
   throw new AssertionError(m.getName());
  });
 }
}