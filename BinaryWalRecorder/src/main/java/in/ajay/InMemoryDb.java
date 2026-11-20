package in.ajay;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDb {

    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

    void put(String key, String value){
        map.put(key, value);
    }

    String get(String key){
        return map.get(key);
    }

    void delete(String key){
        map.remove(key);
    }
    void deleteAll(){ map.clear(); }

    public ConcurrentHashMap<String, String> getAllData() {
        return this.map;
    }
}
