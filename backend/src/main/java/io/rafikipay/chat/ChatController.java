package io.rafikipay.chat;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final GeminiService geminiService;

    public ChatController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    public record ChatRequest(String message, String sessionId) {}
    public record ChatResponse(String reply) {}
    public record ContactDto(String name, String phone) {}

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String userMessage = request != null && request.message() != null ? request.message() : "";
        String reply = geminiService.generateResponse(userMessage);
        return new ChatResponse(reply);
    }

    @GetMapping("/contacts")
    public List<ContactDto> getContacts() {
        return geminiService.getContactsList();
    }

    @PostMapping("/contacts")
    public List<ContactDto> saveContact(@RequestBody ContactDto dto) {
        if (dto != null && dto.name() != null && dto.phone() != null) {
            geminiService.addContact(dto.name(), dto.phone());
        }
        return geminiService.getContactsList();
    }

    @DeleteMapping("/contacts/{name}")
    public List<ContactDto> deleteContact(@PathVariable String name) {
        geminiService.deleteContact(name);
        return geminiService.getContactsList();
    }
}
