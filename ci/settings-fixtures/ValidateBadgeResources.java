import android.content.Context;
import local.wazemaps.badges.BadgeSelector;
public class ValidateBadgeResources {
 public static void main(String[] args) {
  var context=new Context();
  String[] names={"crown","sword","shield","edit",null,null,"wings"};
  for(int generation=0;generation<2;generation++) {
   for(int i=0;i<names.length;i++) if(names[i]!=null) {
    int id=0x7f080100+generation*100+i;
    context.resources.ids.put("drawable/"+names[i],id);
    if(BadgeSelector.drawable(context,i).id!=id) throw new AssertionError("Badge used a stale resource ID");
   }
  }
  context.resources.ids.clear();
  for(int i=-2;i<=7;i++) if(BadgeSelector.drawable(context,i)!=null) throw new AssertionError("Missing/invalid badge did not return null");
  System.out.println("PASS badge artwork resolves by name across resource renumbering");
 }
}
