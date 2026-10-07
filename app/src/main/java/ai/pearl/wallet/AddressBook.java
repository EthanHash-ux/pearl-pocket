package ai.pearl.wallet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AddressBook {
    static final class Contact {
        final String id, name, address;
        Contact(String id, String name, String address) { this.id=id; this.name=name; this.address=address; }
    }
    private final PublicStore.Storage store;
    AddressBook(PublicStore.Storage store) { this.store=store; }
    List<Contact> list() throws Exception { synchronized (PublicStore.LOCK) { return read(); } }
    private List<Contact> read() throws Exception {
        JSONArray json = new JSONArray(store.read("contacts"));
        if (json.length()>200) throw new IllegalArgumentException("地址簿数据过大");
        List<Contact> result = new ArrayList<>();
        for(int i=0;i<json.length();i++) {
            JSONObject item=json.getJSONObject(i);
            result.add(new Contact(item.getString("id"), name(item.getString("name")), PearlAddress.normalize(item.getString("address"))));
        }
        return result;
    }
    static String name(String input) {
        String value=input.trim();
        if(value.isEmpty() || value.length()>40 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("联系人名称需为 1–40 个字符，不能含控制字符");
        return value;
    }
    Contact save(String id, String inputName, String inputAddress) throws Exception {
        String name=name(inputName), address=PearlAddress.normalize(inputAddress);
        synchronized (PublicStore.LOCK) {
            List<Contact> contacts=read();
            for(Contact contact:contacts) if(contact.address.equals(address) && !contact.id.equals(id)) throw new IllegalArgumentException("这个地址已经在地址簿中");
            boolean existing=false;
            for(int i=0;i<contacts.size();i++) if(contacts.get(i).id.equals(id)){contacts.remove(i);existing=true;break;}
            if(id!=null && !existing) throw new IllegalArgumentException("联系人已删除，请重新打开地址簿");
            if(contacts.size()>=200) throw new IllegalArgumentException("地址簿最多保存 200 个联系人");
            Contact next=new Contact(id==null?UUID.randomUUID().toString():id,name,address);contacts.add(next);write(contacts);return next;
        }
    }
    void remove(String id) throws Exception { synchronized (PublicStore.LOCK) { List<Contact> contacts=read(); contacts.removeIf(c->c.id.equals(id));write(contacts); } }
    private void write(List<Contact> contacts) throws Exception {
        JSONArray array=new JSONArray();
        for(Contact c:contacts) array.put(new JSONObject().put("id",c.id).put("name",c.name).put("address",c.address));
        store.write("contacts",array.toString());
    }
}
